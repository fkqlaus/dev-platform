package com.choi.devplatform.git;

import com.choi.devplatform.connection.GitConnection;
import com.choi.devplatform.connection.ConnectionSettings;
import com.choi.devplatform.ssh.SshCommandExecutor;
import com.choi.devplatform.web.ProvisioningException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class SshGitRepositoryProvisioner implements GitRepositoryProvisioner {
    private final SshCommandExecutor executor;
    public SshGitRepositoryProvisioner(SshCommandExecutor executor) { this.executor = executor; }

    @Override public void create(String name, GitConnection connection) {
        var properties = connection.properties();
        String command = RepositoryCommand.build(properties, name, connection.group());
        verify(executor.execute(properties, connection.key(), command), "CREATED", properties.sudoEnabled(), connection.key() != null);
    }
    public void check(ConnectionSettings s, String key) {
        // Validate every interpolated field even when invoked outside the settings controller.
        s.validate();
        String groupCheck = text(s.group()).isEmpty() ? ":" : "case \" $(id -Gn) \" in *' " + s.group() + " '*) ;; *) exit 55;; esac";
        String script = "set -eu\ntest \"$(id -un)\" = '" + s.properties().executionUsername() + "' || exit 51\n"
                + "command -v git >/dev/null 2>&1 || exit 52\n"
                + "test -d '" + s.basePath() + "' || exit 53\n"
                + "test -w '" + s.basePath() + "' && test -x '" + s.basePath() + "' || exit 54\n"
                + groupCheck + "\nprintf 'CHECKED\\n'";
        verify(executor.execute(s.properties(), key, RepositoryCommand.wrap(s.properties(), script)), "CHECKED", s.sudoEnabled(), true);
    }

    private static String text(String value) { return value == null ? "" : value; }
    private static void verify(SshCommandExecutor.Result result, String expected, boolean sudo, boolean savedSettings) {
        String output = result.output();
        if (sudo) {
            if (!output.equals("SUDO_STARTED") && !output.startsWith("SUDO_STARTED\n"))
                throw new ProvisioningException(savedSettings ? HttpStatus.BAD_REQUEST : HttpStatus.BAD_GATEWAY, "sudo 실행을 시작하지 못했습니다. sudo 비밀번호·실행 계정 권한·서버의 터미널 요구 설정을 확인하세요.");
            output = output.substring("SUDO_STARTED".length()).trim();
        }
        Integer code = result.exitCode();
        if (Integer.valueOf(42).equals(code)) throw new ProvisioningException(HttpStatus.CONFLICT, "같은 이름의 경로가 이미 있습니다. 기존 경로는 변경하지 않았습니다.");
        String detail = switch (code == null ? -1 : code) {
            case 51 -> "생성 실행 계정이 다릅니다.";
            case 52 -> "생성 계정에서 Git을 실행할 수 없습니다.";
            case 53 -> "저장소 상위 경로가 없습니다.";
            case 54 -> "저장소 상위 경로에 쓰기·진입 권한이 없습니다.";
            case 55 -> "생성 계정이 지정한 그룹에 속하지 않습니다. 그룹을 확인하거나 비워두세요.";
            default -> "저장소 생성 또는 검증에 실패했습니다. 서버의 경로·권한과 생성된 폴더를 확인하세요. 자동 삭제하지 않습니다.";
        };
        if (!Integer.valueOf(0).equals(code) || !output.equals(expected))
            throw new ProvisioningException(savedSettings ? HttpStatus.BAD_REQUEST : HttpStatus.BAD_GATEWAY, detail);
    }
}
