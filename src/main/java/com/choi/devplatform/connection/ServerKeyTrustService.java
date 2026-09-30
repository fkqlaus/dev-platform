package com.choi.devplatform.connection;

import java.time.*;
import java.util.*;
import java.security.PublicKey;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.springframework.http.HttpStatus;
import com.choi.devplatform.web.ProvisioningException;

@org.springframework.stereotype.Component
public class ServerKeyTrustService {
    private record Trust(String token, String host, int port, String key, Instant expires) {}
    private Trust pending;
    private static ProvisioningException error(String message) { return new ProvisioningException(HttpStatus.BAD_REQUEST, message); }
    public synchronized void clear() { pending = null; }
    public synchronized Map<String, String> observe(String host, int port, PublicKey key, ConnectionSettings saved, String savedKey) {
        String serialized = PublicKeyEntry.toString(key);
        pending = new Trust(UUID.randomUUID().toString(), host, port, serialized, Instant.now().plusSeconds(600));
        boolean changed = saved != null && saved.host().equals(host) && saved.port() == port && !serialized.equals(savedKey);
        return Map.of("token", pending.token(), "algorithm", KeyUtils.getKeyType(key),
                "fingerprint", KeyUtils.getFingerPrint(key), "changed", Boolean.toString(changed));
    }
    public synchronized String trustedKey(ConnectionSettings s, ConnectionSettings saved, String savedKey) {
        if (!s.keyConfirmed() || pending == null || !pending.token().equals(s.trustToken())
                || !pending.host().equals(s.host()) || pending.port() != s.port() || Instant.now().isAfter(pending.expires()))
            throw error("서버 키를 다시 조회하고, 관리자에게 확인한 지문과 비교한 뒤 확인란을 선택하세요.");
        if (saved != null && saved.host().equals(s.host()) && saved.port() == s.port()
                && !savedKey.equals(pending.key()) && !s.replaceChangedKey())
            throw error("저장된 서버 키가 변경됐습니다. 변경 이유를 확인한 뒤 기존 키 교체를 별도로 승인하세요.");
        return pending.key();
    }
}
