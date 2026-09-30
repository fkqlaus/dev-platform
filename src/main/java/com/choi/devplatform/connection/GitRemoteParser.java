package com.choi.devplatform.connection;

import com.choi.devplatform.web.ProvisioningException;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;

final class GitRemoteParser {
    static Map<String, Object> parse(String value) {
        try {
            String host, user, path;
            int port = 22;
            if (value.startsWith("ssh://")) {
                URI uri = URI.create(value);
                host = uri.getHost(); user = uri.getUserInfo(); path = uri.getPath();
                if (uri.getQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException();
                if (uri.getPort() != -1) port = uri.getPort();
            } else {
                var match = java.util.regex.Pattern.compile("([A-Za-z_][A-Za-z0-9_-]*)@([A-Za-z0-9][A-Za-z0-9.-]*):(/.+)").matcher(value);
                if (!match.matches()) throw new IllegalArgumentException();
                user = match.group(1); host = match.group(2); path = match.group(3);
            }
            if (host == null || !host.matches("[A-Za-z0-9][A-Za-z0-9.-]*") || user == null
                    || !user.matches("[A-Za-z_][A-Za-z0-9_-]*") || port < 1 || port > 65535
                    || path == null || !path.matches("/(?:[A-Za-z0-9_-]+/)+[A-Za-z0-9_-]+\\.git")) throw new IllegalArgumentException();
            return Map.of("host", host, "port", port, "username", user, "cloneUsername", user,
                    "basePath", path.substring(0, path.lastIndexOf('/')));
        } catch (Exception e) {
            throw new ProvisioningException(HttpStatus.BAD_REQUEST,
                    "절대 경로가 포함된 SSH 주소를 입력하세요. 예: ssh://git@server:22/repos/project.git. 상대 경로·HTTPS 주소는 지원하지 않습니다.");
        }
    }
}
