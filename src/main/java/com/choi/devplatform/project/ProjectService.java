package com.choi.devplatform.project;

import com.choi.devplatform.connection.ConnectionService;
import com.choi.devplatform.git.GitRepositoryProvisioner;
import com.choi.devplatform.git.RepositoryCommand;
import org.springframework.stereotype.Service;

@Service
public class ProjectService {
    private final GitRepositoryProvisioner provisioner;
    private final ConnectionService connections;
    public ProjectService(GitRepositoryProvisioner provisioner, ConnectionService connections) {
        this.provisioner = provisioner;
        this.connections = connections;
    }
    public ProjectResponse create(CreateProjectRequest request) {
        RepositoryCommand.validateName(request.name());
        var selected = connections.selected();
        provisioner.create(request.name(), selected);
        var properties = selected.properties();
        String path = properties.basePath() + "/" + request.name() + ".git";
        return new ProjectResponse(request.name(), path,
                "ssh://" + properties.effectiveCloneUsername() + "@" + properties.host() + ":" + properties.port() + path);
    }
}
