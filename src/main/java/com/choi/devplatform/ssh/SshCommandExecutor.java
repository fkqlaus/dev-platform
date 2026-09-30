package com.choi.devplatform.ssh;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.PublicKey;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.session.ClientSession.ClientSessionEvent;
import org.apache.sshd.client.keyverifier.*;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import com.choi.devplatform.connection.GitServerProperties;
import com.choi.devplatform.web.ProvisioningException;

@Component
public class SshCommandExecutor implements DisposableBean {
    public record Result(Integer exitCode, String output) {
        @Override public String toString() { return "SshResult[output=REDACTED]"; }
    }
    private SshClient client;
    private boolean destroyed;

    // All client operations are serialized so verifier changes cannot cross requests.
    public synchronized SshClient sharedClient() {
        if (destroyed) throw new ProvisioningException(HttpStatus.SERVICE_UNAVAILABLE, "애플리케이션이 종료 중입니다.");
        if (client == null) {
            var candidate = SshClient.setUpDefaultClient();
            var factories = new ArrayList<>(candidate.getSignatureFactories());
            factories.sort(Comparator.comparingInt(f -> "ssh-ed25519".equals(f.getName()) ? 0 : 1));
            candidate.setSignatureFactories(factories);
            candidate.setServerKeyVerifier(RejectAllServerKeyVerifier.INSTANCE);
            try { candidate.start(); client = candidate; }
            catch (RuntimeException e) { candidate.stop(); throw e; }
        }
        return client;
    }
    public synchronized PublicKey observe(String host, int port) {
        var active = sharedClient();
        var observed = new AtomicReference<PublicKey>();
        active.setServerKeyVerifier((session, address, key) -> { observed.set(key); return true; });
        try (var session = active.connect("key-discovery", host, port).verify(Duration.ofSeconds(15)).getSession()) {
            awaitKey(session, Duration.ofSeconds(15));
            if (observed.get() == null) throw new IOException();
            return observed.get();
        } catch (Exception e) {
            throw new ProvisioningException(HttpStatus.BAD_REQUEST, "서버 키를 조회하지 못했습니다. 주소·포트·네트워크를 확인하세요.");
        } finally { active.setServerKeyVerifier(RejectAllServerKeyVerifier.INSTANCE); }
    }
    public synchronized Result execute(GitServerProperties settings, String pinnedKey, String command) {
        if (settings.host() == null || !settings.host().matches("[A-Za-z0-9][A-Za-z0-9.-]*")
                || settings.port() < 1 || settings.port() > 65535
                || settings.timeoutSeconds() < 1 || settings.timeoutSeconds() > 120
                || settings.password() == null || settings.password().isBlank()
                || (pinnedKey == null && (settings.knownHosts() == null || settings.knownHosts().isBlank()
                || !Files.isRegularFile(Path.of(settings.knownHosts()))))) {
            throw new ProvisioningException(HttpStatus.SERVICE_UNAVAILABLE, "Git 연결 환경변수와 검증된 known_hosts 파일을 설정하세요.");
        }
        var active = sharedClient();
        var timeout = Duration.ofSeconds(settings.timeoutSeconds());
        String phase = "서버 키 검증 또는 SSH 연결";
        try {
            active.setServerKeyVerifier(pinnedKey == null
                    ? new KnownHostsServerKeyVerifier(RejectAllServerKeyVerifier.INSTANCE, Path.of(settings.knownHosts()))
                    : (session, address, key) -> PublicKeyEntry.toString(key).equals(pinnedKey));
            try (var session = active.connect(settings.username(), settings.host(), settings.port()).verify(timeout).getSession()) {
                awaitKey(session, timeout);
                phase = "SSH 비밀번호 인증";
                session.addPasswordIdentity(settings.password());
                session.auth().verify(timeout);
                phase = "원격 명령 실행";
                try (var channel = session.createExecChannel(command)) {
                    var out = new ByteArrayOutputStream();
                    channel.setOut(out);
                    channel.setErr(OutputStream.nullOutputStream());
                    if (settings.sudoEnabled()) channel.setIn(new ByteArrayInputStream((settings.effectiveSudoPassword() + "\n").getBytes(StandardCharsets.UTF_8)));
                    channel.open().verify(timeout);
                    if (channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), timeout).contains(ClientChannelEvent.TIMEOUT))
                        throw new ProvisioningException(HttpStatus.GATEWAY_TIMEOUT, "응답 시간이 초과됐습니다. 생성 요청이었다면 저장소 생성 여부를 확인한 뒤 재시도하세요.");
                    return new Result(channel.getExitStatus(), out.toString(StandardCharsets.UTF_8).trim());
                }
            }
        } catch (ProvisioningException e) { throw e; }
        catch (Exception e) { throw new ProvisioningException(HttpStatus.BAD_GATEWAY, phase + "에 실패했습니다. 연결 설정을 확인하세요."); }
        finally { active.setServerKeyVerifier(RejectAllServerKeyVerifier.INSTANCE); }
    }
    private static void awaitKey(ClientSession session, Duration timeout) throws IOException {
        var events = session.waitFor(EnumSet.of(ClientSessionEvent.WAIT_AUTH, ClientSessionEvent.CLOSED), timeout);
        if (!events.contains(ClientSessionEvent.WAIT_AUTH) || events.contains(ClientSessionEvent.CLOSED)) throw new IOException();
    }
    @Override public synchronized void destroy() {
        destroyed = true;
        if (client != null) client.stop();
    }
}
