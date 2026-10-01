package com.choi.devplatform.git;

import com.choi.devplatform.connection.GitServerProperties;
import com.choi.devplatform.web.ProvisioningException;
import org.springframework.http.HttpStatus;

public final class RepositoryCommand {
    private RepositoryCommand() {}

    public static String build(GitServerProperties properties, String name) {
        return build(properties, name, "users");
    }

    public static String build(GitServerProperties properties, String name, String group) {
        String script = build(properties.basePath(), name, properties.executionUsername());
        if (group == null || !group.matches("[a-zA-Z_][a-zA-Z0-9_-]*|")) {
            throw new ProvisioningException(HttpStatus.BAD_REQUEST, "저장소 그룹 형식을 확인하세요.");
        }
        script = script.replace("chgrp -- users \"$repo\" || exit 43",
                group.isEmpty() ? ":" : "chgrp -- '" + group + "' \"$repo\" || exit 43");
        return wrap(properties, script);
    }

    public static String wrap(GitServerProperties properties, String script) {
        if (!properties.effectiveCloneUsername().matches("[a-zA-Z_][a-zA-Z0-9_-]*")) {
            throw new ProvisioningException(HttpStatus.SERVICE_UNAVAILABLE, "Clone 계정 설정을 확인하세요.");
        }
        if (!properties.sudoEnabled()) return script;
        String password = properties.effectiveSudoPassword();
        if (password == null || password.isEmpty() || password.contains("\n") || password.contains("\r")
                || password.indexOf(0) >= 0) {
            throw new ProvisioningException(HttpStatus.SERVICE_UNAVAILABLE, "sudo 비밀번호 설정을 확인하세요. 줄바꿈은 사용할 수 없습니다.");
        }
        // Keep the password out of command arguments; stdin is reserved for sudo authentication.
        script = "printf 'SUDO_STARTED\\n'\nexec </dev/null\n" + script;
        return "sudo -k -S -p '' -u '" + properties.sudoUsername()
                + "' -- /bin/sh -c '" + script.replace("'", "'\"'\"'") + "'";
    }

    public static void validateName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
            throw new ProvisioningException(HttpStatus.BAD_REQUEST, "프로젝트 이름 형식을 확인하세요. .git은 자동으로 붙습니다.");
        }
    }

    public static String build(String basePath, String name, String username) {
        validateName(name);
        if (basePath == null || !basePath.matches("/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+")
                || username == null || !username.matches("[a-zA-Z_][a-zA-Z0-9_-]*")) {
            throw new ProvisioningException(HttpStatus.SERVICE_UNAVAILABLE, "Git 서버 경로 또는 계정 설정을 확인하세요.");
        }
        // mkdir is the atomic reservation: never initialize, chmod or remove an existing path.
        // A failed operation keeps its newly created directory for manual inspection.
        return """
                set -eu
                test "$(id -un)" = '%s' || exit 44
                cd '%s' || exit 44
                command -v git >/dev/null 2>&1 || exit 44
                repo='%s.git'
                if ! mkdir -m 775 -- "$repo"; then
                    if [ -e "$repo" ] || [ -L "$repo" ]; then exit 42; fi
                    exit 44
                fi
                umask 002
                chgrp -- users "$repo" || exit 43
                git init --bare -- "$repo" >/dev/null 2>&1 || exit 43
                chmod 775 -- "$repo" || exit 43
                test "$(git --git-dir="$repo" rev-parse --is-bare-repository)" = true || exit 43
                test -O "$repo" || exit 43
                printf 'CREATED\\n'
                """.formatted(username, basePath, name);
    }
}
