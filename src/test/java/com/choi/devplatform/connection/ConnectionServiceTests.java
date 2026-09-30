package com.choi.devplatform.connection;

import com.choi.devplatform.connection.*;
import com.choi.devplatform.git.*;
import com.choi.devplatform.project.*;
import com.choi.devplatform.ssh.SshCommandExecutor;
import com.choi.devplatform.web.ProvisioningException;


import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.sshd.server.*;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionServiceTests {
    @TempDir Path directory;
    SshServer server;
    ConnectionService service;
    SshCommandExecutor executor;
    SshGitRepositoryProvisioner provisioner;
    ConnectionService service() {
        executor = new SshCommandExecutor();
        provisioner = new SshGitRepositoryProvisioner(executor);
        return new ConnectionService(fallback, new ConnectionSettingsFile(directory.resolve("connection.properties")),
                new ServerKeyTrustService(), executor, provisioner);
    }
    ProjectResponse create(String name) {
        return new ProjectService(provisioner, service).create(new CreateProjectRequest(name));
    }
    AtomicInteger authentications = new AtomicInteger();
    AtomicInteger commands = new AtomicInteger();
    int exitCode;
    String lastCommand;
    volatile String receivedInput;
    final GitServerProperties fallback = new GitServerProperties("old.example",22,"operator","test-only","/srv/git","",15,false,"git","","");
    @BeforeEach void start() throws Exception {
        server = SshServer.setUpDefaultServer(); server.setHost("127.0.0.1"); server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(directory.resolve("host")));
        server.setPasswordAuthenticator((user,password,session) -> { authentications.incrementAndGet(); return user.equals("operator") && password.equals("test-only"); });
        server.setCommandFactory((channel,command) -> new Command() {
            OutputStream out; ExitCallback callback; InputStream input;
            public void setInputStream(InputStream in) { input=in; }
            public void setOutputStream(OutputStream value) { out=value; }
            public void setErrorStream(OutputStream err) {}
            public void setExitCallback(ExitCallback value) { callback=value; }
            public void start(ChannelSession channel, Environment environment) throws IOException {
                commands.incrementAndGet(); lastCommand=command;
                if (command.startsWith("sudo ")) {
                    Thread.startVirtualThread(() -> {
                        try {
                            receivedInput = new String(input.readAllBytes(),StandardCharsets.UTF_8);
                            out.write("SUDO_STARTED\n".getBytes(StandardCharsets.UTF_8));
                            respond(command);
                        } catch(IOException e) { callback.onExit(1); }
                    });
                    return;
                }
                respond(command);
            }
            private void respond(String command) throws IOException {
                String response=exitCode == 0 ? command.contains("CHECKED") ? "CHECKED\n" : "CREATED\n" : "";
                out.write(response.getBytes(StandardCharsets.UTF_8)); out.flush(); callback.onExit(exitCode);
            }
            public void destroy(ChannelSession channel) {}
        });
        server.start(); service = service();
    }
    @AfterEach void stop() throws Exception {executor.destroy(); server.stop(true);}
    ConnectionSettings settings(String token, boolean confirmed, int port) {
        return new ConnectionSettings("127.0.0.1",port,"operator","test-only","/srv/git",false,"git","","operator","",token,confirmed,false);
    }
    String discover() { return service.discover("127.0.0.1",server.getPort()).get("token"); }
    @Test void discoveryDoesNotAuthenticateOrExecute() {
        assertNotNull(discover()); assertEquals(0,authentications.get()); assertEquals(0,commands.get());
    }
    @Test void requiresExplicitApprovalBoundToEndpoint() {
        String token=discover();
        assertThrows(ProvisioningException.class,()->service.check(settings(token,false,server.getPort())));
        assertThrows(ProvisioningException.class,()->service.check(settings(token,true,server.getPort()+1)));
        assertEquals(0,authentications.get());
    }
    @Test void checkedSavedConnectionSurvivesRestartAndCreatesWithCorrectUrl() {
        service.save(settings(discover(),true,server.getPort()));
        assertTrue(service.hasSavedConnection());
        assertFalse(service.defaults().containsKey("password"));
        assertFalse(lastCommand.contains("mkdir"));
        executor.destroy(); service=service();
        assertTrue(service.hasSavedConnection());
        assertEquals("ssh://operator@127.0.0.1:"+server.getPort()+"/srv/git/sample.git",create("sample").cloneUrl());
        assertFalse(lastCommand.contains("chgrp"));
    }
    @Test void checkDoesNotPersistAndReportsMissingPath() {
        exitCode=53;
        var error=assertThrows(ProvisioningException.class,()->service.check(settings(discover(),true,server.getPort())));
        assertTrue(error.getMessage().contains("상위 경로가 없습니다"));
        assertFalse(Files.exists(directory.resolve("connection.properties")));
    }
    @Test void failedReplacementPreservesPreviousSavedConnection() {
        service.save(settings(discover(),true,server.getPort()));
        exitCode=54;
        assertThrows(ProvisioningException.class,()->service.save(settings(discover(),true,server.getPort())));
        assertTrue(service.hasSavedConnection());
        exitCode=0; assertNotNull(create("sample"));
    }
    @Test void changedKeyRejectedBeforePasswordAuthentication() {
        service.save(settings(discover(),true,server.getPort()));
        int previous=authentications.get();
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(directory.resolve("replacement-host")));
        assertThrows(ProvisioningException.class,()->create("sample"));
        assertEquals(previous,authentications.get());
        assertEquals("true",service.discover("127.0.0.1",server.getPort()).get("changed"));
        assertThrows(ProvisioningException.class, () -> service.save(settings(discover(),true,server.getPort())));
        assertEquals(previous,authentications.get());
    }
    @Test void malformedSavedFileBlocksFallback() throws Exception {
        executor.destroy(); Files.writeString(directory.resolve("connection.properties"),"bad");
        service=service();
        assertThrows(ProvisioningException.class,()->service.hasSavedConnection());
    }
    @Test void parserHandlesPortAndRejectsRelativePathOrInjection() {
        assertEquals(2222,GitRemoteParser.parse("ssh://git@example.test:2222/repos/my-app.git").get("port"));
        assertEquals("/repos",GitRemoteParser.parse("git@example.test:/repos/my-app.git").get("basePath"));
        assertThrows(ProvisioningException.class,()->GitRemoteParser.parse("git@example.test:repos/my-app.git"));
        assertThrows(ProvisioningException.class,()->GitRemoteParser.parse("ssh://git@example.test/repos/../my-app.git"));
        assertThrows(ProvisioningException.class,()->GitRemoteParser.parse("https://example.test/repos/my-app.git"));
    }
    @Test void canonicalEndpointCannotBypassChangedKeyApproval() {
        assertEquals("git.local", ConnectionSettings.canonicalHost("GIT.LOCAL."));
        service.save(settings(discover(),true,server.getPort()));
        int previous=authentications.get();
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(directory.resolve("new-host")));
        var observation=service.discover("127.0.0.1.",server.getPort());
        assertEquals("true",observation.get("changed"));
        assertThrows(ProvisioningException.class,()->service.check(settings(observation.get("token"),true,server.getPort())));
        assertEquals(previous,authentications.get());
    }
    @Test void sudoCheckUsesStdinAndSaveRestrictsLocalFilePermissions() throws Exception {
        String token=discover();
        var settings=new ConnectionSettings("127.0.0.1",server.getPort(),"operator","test-only","/srv/git",
                true,"git","sudo-test-only","git","users",token,true,false);
        service.save(settings);
        assertTrue("sudo-test-only\n".equals(receivedInput));
        assertFalse(lastCommand.contains("sudo-test-only"));
        assertTrue(create("sample").cloneUrl().startsWith("ssh://git@"));
        Path file=directory.resolve("connection.properties");
        var acl=Files.getFileAttributeView(file,java.nio.file.attribute.AclFileAttributeView.class);
        if(acl!=null) {
            assertEquals(1,acl.getAcl().size());
            assertEquals(acl.getOwner(),acl.getAcl().getFirst().principal());
        } else {
            assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(file));
        }
    }
}
