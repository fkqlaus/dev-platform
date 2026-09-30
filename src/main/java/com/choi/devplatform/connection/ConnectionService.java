package com.choi.devplatform.connection;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import com.choi.devplatform.web.ProvisioningException;

import com.choi.devplatform.git.SshGitRepositoryProvisioner;
import com.choi.devplatform.ssh.SshCommandExecutor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

@Service
@EnableConfigurationProperties(GitServerProperties.class)
public class ConnectionService {
    private final GitServerProperties fallback;
    private final ConnectionSettingsFile file;
    private final ServerKeyTrustService trust;
    private final SshCommandExecutor executor;
    private final SshGitRepositoryProvisioner provisioner;
    private ConnectionSettings saved;
    private String savedKey;
    private boolean loadFailed;

    public ConnectionService(GitServerProperties fallback, ConnectionSettingsFile file,
            ServerKeyTrustService trust, SshCommandExecutor executor, SshGitRepositoryProvisioner provisioner) {
        this.fallback = fallback;
        this.file = file;
        this.trust = trust;
        this.executor = executor;
        this.provisioner = provisioner;
        try {
            var stored = file.load();
            if (stored != null) {
                saved = stored.settings();
                savedKey = stored.key();
            }
        } catch (ProvisioningException e) {
            loadFailed = true;
        }
    }

    private static ProvisioningException error(String message) { return new ProvisioningException(HttpStatus.BAD_REQUEST, message); }
    private static String text(String value) { return value == null ? "" : value; }
    public synchronized Map<String, Object> defaults() {
        var p = saved == null ? fallback : saved.properties();
        var result = new LinkedHashMap<String, Object>();
        result.put("saved", saved != null);
        result.put("loadFailed", loadFailed);
        result.put("host", text(p.host()));
        result.put("port", p.port());
        result.put("username", text(p.username()));
        result.put("basePath", text(p.basePath()));
        result.put("sudoEnabled", p.sudoEnabled());
        result.put("sudoUsername", text(p.sudoUsername()));
        result.put("cloneUsername", text(p.effectiveCloneUsername()));
        result.put("group", saved == null ? "" : text(saved.group()));
        return result;
    }

    public synchronized Map<String, String> discover(String host, int port) {
        host = ConnectionSettings.canonicalHost(host);
        ConnectionSettings.validateEndpoint(host, port);
        trust.clear();
        return trust.observe(host, port, executor.observe(host, port), saved, savedKey);
    }

    private String trustedKey(ConnectionSettings settings) {
        settings.validate();
        return trust.trustedKey(settings, saved, savedKey);
    }

    public synchronized Map<String, Object> check(ConnectionSettings settings) {
        provisioner.check(settings, trustedKey(settings));
        return Map.of("ok", true, "message", "서버 키·SSH 인증·실행 계정·Git·경로·쓰기 권한·그룹 조건을 확인했습니다. 실제 저장소 생성과 clone·push는 별도 확인하세요.");
    }

    public synchronized void save(ConnectionSettings settings) {
        String key = trustedKey(settings);
        provisioner.check(settings, key);
        file.save(settings, key);
        saved = settings;
        savedKey = key;
        loadFailed = false;
        trust.clear();
    }

    public synchronized boolean hasSavedConnection() {
        if (loadFailed) throw error("저장한 연결 파일을 읽을 수 없습니다. 연결 설정을 다시 저장하세요.");
        return saved != null;
    }

    public synchronized GitConnection selected() {
        return hasSavedConnection()
                ? new GitConnection(saved.properties(), savedKey, text(saved.group()))
                : new GitConnection(fallback, null, "users");
    }
}
