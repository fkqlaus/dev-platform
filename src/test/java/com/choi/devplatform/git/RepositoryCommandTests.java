package com.choi.devplatform.git;

import com.choi.devplatform.connection.*;
import com.choi.devplatform.git.*;
import com.choi.devplatform.project.*;
import com.choi.devplatform.ssh.SshCommandExecutor;
import com.choi.devplatform.web.ProvisioningException;


import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class RepositoryCommandTests {
    @ParameterizedTest
    @ValueSource(strings = {"../escape", "a/b", "x;id", "x$(id)", "-option", "name.git", "", "a b", "한글", "a\nb"})
    void rejectsUnsafeNames(String name) {
        assertThrows(ProvisioningException.class, () -> RepositoryCommand.build("/srv/git", name, "git"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Sample-Homepage", "a", "sample_api", "123"})
    void allowsProjectNames(String name) {
        assertDoesNotThrow(() -> RepositoryCommand.build("/srv/git", name, "git"));
    }
}
