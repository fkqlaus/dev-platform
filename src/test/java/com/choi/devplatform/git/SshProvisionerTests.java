package com.choi.devplatform.git;

import com.choi.devplatform.connection.*;
import com.choi.devplatform.git.*;
import com.choi.devplatform.project.*;
import com.choi.devplatform.ssh.SshCommandExecutor;
import com.choi.devplatform.web.ProvisioningException;


import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;

class SshProvisionerTests {
    @TempDir Path directory;
    SshServer server;
    Path knownHosts;
    AtomicInteger executions = new AtomicInteger();
    int exitCode;
    boolean hang;
    boolean readSudoInput;
    volatile String receivedInput;
    volatile String receivedCommand;
    String response = "CREATED\n";
    java.util.List<Harness> provisioners = new java.util.ArrayList<>();

    // Test fixture owns lifecycle and settings; production has one shared executor bean.
    private static class Harness {
        final SshCommandExecutor executor = new SshCommandExecutor();
        final SshGitRepositoryProvisioner provisioner = new SshGitRepositoryProvisioner(executor);
        final GitServerProperties properties;
        Harness(GitServerProperties properties) { this.properties = properties; }
        void create(String name) { provisioner.create(name, new GitConnection(properties, null, "users")); }
        org.apache.sshd.client.SshClient sharedClient() { return executor.sharedClient(); }
        void destroy() { executor.destroy(); }
    }

    @BeforeEach void startServer(TestInfo testInfo) throws Exception {
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        var keys = new SimpleGeneratorHostKeyProvider(directory.resolve("host-key"));
        if (testInfo.getTestMethod().orElseThrow().getName().startsWith("ed25519")) {
            keys.setAlgorithm("Ed25519");
        }
        server.setKeyPairProvider(keys);
        if (testInfo.getTestMethod().orElseThrow().getName().equals("ed25519KnownHostWinsOverEcdsa")) {
            var ecKeys = new SimpleGeneratorHostKeyProvider(directory.resolve("ec-key"));
            ecKeys.setAlgorithm("EC");
            var keyPairs = java.util.List.of(keys.loadKeys(null).iterator().next(),
                    ecKeys.loadKeys(null).iterator().next());
            server.setKeyPairProvider(session -> keyPairs);
        }
        server.setPasswordAuthenticator((user, password, session) -> user.equals("git") && password.equals("test-only"));
        server.setCommandFactory((channel, command) -> new Command() {
            InputStream in;
            OutputStream out;
            ExitCallback callback;
            public void setInputStream(InputStream in) { this.in = in; }
            public void setOutputStream(OutputStream out) { this.out = out; }
            public void setErrorStream(OutputStream err) {}
            public void setExitCallback(ExitCallback callback) { this.callback = callback; }
            public void start(ChannelSession channel, Environment env) throws IOException {
                executions.incrementAndGet();
                receivedCommand = command;
                if (hang) return;
                if (readSudoInput) {
                    Thread.startVirtualThread(() -> {
                        try {
                            receivedInput = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                            out.write(response.getBytes(StandardCharsets.UTF_8));
                            out.flush();
                            callback.onExit(exitCode);
                        } catch (IOException e) { callback.onExit(1); }
                    });
                    return;
                }
                out.write(response.getBytes(StandardCharsets.UTF_8));
                out.flush();
                callback.onExit(exitCode);
            }
            public void destroy(ChannelSession channel) {}
        });
        server.start();
        knownHosts = directory.resolve("known_hosts");
        Files.writeString(knownHosts, "[127.0.0.1]:" + server.getPort() + " "
                + PublicKeyEntry.toString(keys.loadKeys(null).iterator().next().getPublic()) + "\n");
    }

    @AfterEach void stopServer() throws Exception {
        provisioners.forEach(Harness::destroy);
        if (server != null) server.stop(true);
    }

    Harness provisioner(String password) {
        var provisioner = new Harness(new GitServerProperties("127.0.0.1", server.getPort(),
                "git", password, "/srv/git", knownHosts.toString(), 3, false, "git", "", ""));
        provisioners.add(provisioner);
        return provisioner;
    }

    Harness sudoProvisioner(String sudoPassword) {
        var provisioner = new Harness(new GitServerProperties("127.0.0.1", server.getPort(),
                "git", "test-only", "/srv/git", knownHosts.toString(), 3,
                true, "git", sudoPassword, "git"));
        provisioners.add(provisioner);
        return provisioner;
    }

    @Test void sudoPasswordTravelsOnlyOverStdinWithEof() {
        readSudoInput = true;
        response = "SUDO_STARTED\nCREATED\n";
        String password = "test-'-$()-only";
        sudoProvisioner(password).create("sample");
        assertTrue((password + "\n").equals(receivedInput));
        assertFalse(receivedCommand.contains(password));
        assertTrue(receivedCommand.startsWith("sudo -k -S -p '' -u 'git' -- /bin/sh -c '"));
    }

    @Test void sudoCanReuseSshPassword() {
        readSudoInput = true;
        response = "SUDO_STARTED\nCREATED\n";
        sudoProvisioner("").create("sample");
        assertTrue("test-only\n".equals(receivedInput));
    }

    @Test void sudoFailureIsDistinctAndDoesNotExposeRawOutput() {
        response = "";
        exitCode = 1;
        var error = assertThrows(ProvisioningException.class, () -> sudoProvisioner("test-only").create("sample"));
        assertTrue(error.getMessage().contains("sudo 실행을 시작하지 못했습니다"));
        assertFalse(error.getMessage().contains("test-only"));
    }

    @Test void sudoExistingRepositoryStillReturnsConflict() {
        response = "SUDO_STARTED\n";
        exitCode = 42;
        assertEquals(HttpStatus.CONFLICT, assertThrows(ProvisioningException.class,
                () -> sudoProvisioner("test-only").create("existing")).status());
    }

    @Test void sudoCreationFailureIsNotAuthenticationFailure() {
        response = "SUDO_STARTED\n";
        exitCode = 43;
        var error = assertThrows(ProvisioningException.class, () -> sudoProvisioner("test-only").create("sample"));
        assertTrue(error.getMessage().contains("저장소 생성 또는 검증"));
    }

    @Test void multilineSudoPasswordRejectedBeforeConnection() {
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ProvisioningException.class,
                () -> sudoProvisioner("test\nextra").create("sample")).status());
        assertEquals(0, executions.get());
    }

    @Test void sequentialRequestsReuseClientAfterSuccessAndFailure() {
        var provisioner = provisioner("test-only");
        var client = provisioner.sharedClient();
        for (int i = 0; i < 10; i++) provisioner.create("sample" + i);
        exitCode = 42;
        assertThrows(ProvisioningException.class, () -> provisioner.create("existing"));
        exitCode = 0;
        provisioner.create("next");
        assertSame(client, provisioner.sharedClient());
        assertTrue(client.isStarted());
        assertEquals(12, executions.get());
        provisioner.destroy();
        assertFalse(client.isStarted());
        assertThrows(ProvisioningException.class, () -> provisioner.create("afterShutdown"));
        assertEquals(12, executions.get());
    }

    @Test void concurrentRequestsKeepSharedClientAlive() throws Exception {
        var provisioner = provisioner("test-only");
        var client = provisioner.sharedClient();
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) {
                final int index = i;
                futures.add(workers.submit(() -> provisioner.create("parallel" + index)));
            }
            for (var future : futures) future.get(15, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertSame(client, provisioner.sharedClient());
        assertTrue(client.isStarted());
        assertEquals(8, executions.get());
    }

    @Test void verifiedHostAndPasswordCanExecute() {
        assertDoesNotThrow(() -> provisioner("test-only").create("sample"));
        assertEquals(1, executions.get());
    }

    @Test void pinnedAndKnownHostsRequestsDoNotShareTrust() throws Exception {
        var fixture = provisioner("test-only");
        String key = PublicKeyEntry.toString(server.getKeyPairProvider().loadKeys(null).iterator().next().getPublic());
        var pinned = new GitConnection(fixture.properties, key, "");
        fixture.provisioner.create("pinned", pinned);
        Files.writeString(knownHosts, "");
        assertThrows(ProvisioningException.class, () -> fixture.create("untrusted"));
        fixture.provisioner.create("pinnedAgain", pinned);
        assertEquals(2, executions.get());
    }

    @Test void observingKeyDoesNotApproveLaterExecution() throws Exception {
        var fixture = provisioner("test-only");
        assertNotNull(fixture.executor.observe("127.0.0.1", server.getPort()));
        assertEquals(0, executions.get());
        Files.writeString(knownHosts, "");
        assertThrows(ProvisioningException.class, () -> fixture.create("untrusted"));
        assertEquals(0, executions.get());
    }

    @Test void mixedConcurrentTrustRequestsStayIsolated() throws Exception {
        var fixture = provisioner("test-only");
        String key = PublicKeyEntry.toString(server.getKeyPairProvider().loadKeys(null).iterator().next().getPublic());
        var pinned = new GitConnection(fixture.properties, key, "");
        Files.writeString(knownHosts, "");
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 4; i++) {
                final int index = i;
                futures.add(workers.submit(() -> fixture.provisioner.create("trusted" + index, pinned)));
                futures.add(workers.submit(() -> assertThrows(ProvisioningException.class,
                        () -> fixture.create("untrusted" + index))));
            }
            for (var future : futures) future.get(15, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(4, executions.get());
    }

    @Test void ed25519HostCanExecute() throws Exception {
        assertTrue(Files.readString(knownHosts).contains("ssh-ed25519"));
        assertDoesNotThrow(() -> provisioner("test-only").create("sample"));
        assertEquals(1, executions.get());
    }

    @Test void ed25519KnownHostWinsOverEcdsa() throws Exception {
        assertTrue(Files.readString(knownHosts).contains("ssh-ed25519"));
        assertDoesNotThrow(() -> provisioner("test-only").create("sample"));
        assertEquals(1, executions.get());
    }

    @Test void existingPathMapsToConflict() {
        exitCode = 42;
        assertEquals(HttpStatus.CONFLICT, assertThrows(ProvisioningException.class,
                () -> provisioner("test-only").create("sample")).status());
    }

    @Test void initializationFailureDoesNotReportSuccess() {
        exitCode = 43;
        assertEquals(HttpStatus.BAD_GATEWAY, assertThrows(ProvisioningException.class,
                () -> provisioner("test-only").create("sample")).status());
    }

    @Test void unexpectedOutputDoesNotReportSuccess() {
        response = "";
        assertThrows(ProvisioningException.class, () -> provisioner("test-only").create("sample"));
    }

    @Test void unknownHostNeverExecutes() throws Exception {
        Files.writeString(knownHosts, "");
        assertThrows(ProvisioningException.class, () -> provisioner("test-only").create("sample"));
        assertEquals(0, executions.get());
    }

    @Test void changedHostNeverExecutes() throws Exception {
        var other = new SimpleGeneratorHostKeyProvider(directory.resolve("other-key"));
        Files.writeString(knownHosts, "[127.0.0.1]:" + server.getPort() + " "
                + PublicKeyEntry.toString(other.loadKeys(null).iterator().next().getPublic()) + "\n");
        assertThrows(ProvisioningException.class, () -> provisioner("test-only").create("sample"));
        assertEquals(0, executions.get());
    }

    @Test void incorrectPasswordNeverExecutes() {
        assertThrows(ProvisioningException.class, () -> provisioner("wrong").create("sample"));
        assertEquals(0, executions.get());
    }

    @Test void missingConfigurationNeverExecutes() {
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ProvisioningException.class,
                () -> provisioner("").create("sample")).status());
        assertEquals(0, executions.get());
    }

    @Test void executionTimeoutReportsUncertainOutcome() {
        hang = true;
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, assertThrows(ProvisioningException.class,
                () -> provisioner("test-only").create("sample")).status());
        assertEquals(1, executions.get());
    }
}
