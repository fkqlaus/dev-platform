package com.choi.devplatform.git;

import com.choi.devplatform.connection.GitConnection;

public interface GitRepositoryProvisioner {
    void create(String name, GitConnection connection);
}
