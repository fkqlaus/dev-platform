package com.choi.devplatform.git;

import com.choi.devplatform.connection.*;
import com.choi.devplatform.git.*;
import com.choi.devplatform.project.*;
import com.choi.devplatform.ssh.SshCommandExecutor;
import com.choi.devplatform.web.ProvisioningException;


import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SudoConfigurationTests {
    private ProjectService service(GitServerProperties settings) {
        var connections = org.mockito.Mockito.mock(ConnectionService.class);
        org.mockito.Mockito.when(connections.selected()).thenReturn(new GitConnection(settings, null, "users"));
        return new ProjectService((name, connection) -> {}, connections);
    }
    private GitServerProperties properties(boolean sudo, String target, String clone) {
        return new GitServerProperties("example.test", 22, "operator", "test-only",
                "/srv/git", "known_hosts", 15, sudo, target, "", clone);
    }

    @Test void cloneUsesExecutionAccountInsteadOfSshAccount() {
        var service = service(properties(true, "git", ""));
        assertEquals("ssh://git@example.test:22/srv/git/sample.git",
                service.create(new CreateProjectRequest("sample")).cloneUrl());
    }

    @Test void explicitCloneAccountIsIndependent() {
        var service = service(properties(true, "git", "developer"));
        assertEquals("ssh://developer@example.test:22/srv/git/sample.git",
                service.create(new CreateProjectRequest("sample")).cloneUrl());
    }

    @Test void directModeKeepsSshExecutionAndCloneAccount() {
        var config = properties(false, "git", "");
        assertEquals("operator", config.effectiveCloneUsername());
        assertFalse(RepositoryCommand.build(config, "sample").contains("sudo"));
    }

    @Test void targetAccountCannotInjectShellCommands() {
        assertThrows(ProvisioningException.class,
                () -> RepositoryCommand.build(properties(true, "git';id", "git"), "sample"));
    }

    @Test void configurationToStringDoesNotExposePasswords() {
        assertFalse(properties(true, "git", "").toString().contains("test-only"));
    }
}
