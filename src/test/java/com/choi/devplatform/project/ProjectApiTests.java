package com.choi.devplatform.project;

import com.choi.devplatform.connection.*;
import com.choi.devplatform.git.*;
import com.choi.devplatform.web.ProvisioningException;


import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.HttpStatus;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"platform.git.host=git.example.test", "platform.git.username=git",
        "platform.git.port=22", "platform.git.base-path=/srv/git"})
@AutoConfigureMockMvc
class ProjectApiTests {
    @Autowired MockMvc mvc;
    @MockitoBean GitRepositoryProvisioner provisioner;
    @MockitoBean ConnectionService connections;

    @org.junit.jupiter.api.BeforeEach void settings() {
        when(connections.selected()).thenReturn(new GitConnection(
                new GitServerProperties("git.example.test", 22, "git", "", "/srv/git", "", 15, false, "git", "", ""), null, "users"));
    }

    @Test void sourceExecutionDoesNotExposeExitAction() throws Exception {
        mvc.perform(get("/api/application")).andExpect(status().isOk())
                .andExpect(jsonPath("$.packaged").value(false));
        mvc.perform(post("/api/application/exits").contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test void foreignOriginCannotRequestApplicationExit() throws Exception {
        mvc.perform(post("/api/application/exits").header("Origin", "https://foreign.example")
                .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test void rejectsForeignSettingsWrite() throws Exception {
        mvc.perform(put("/api/connection").header("Origin", "https://foreign.example")
                .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(connections);
    }

    @Test void parsesExistingRemoteForSetup() throws Exception {
        mvc.perform(post("/api/git-remotes/parse").contentType("application/json")
                .content("{\"url\":\"ssh://git@example.test:2222/repos/sample.git\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.port").value(2222))
                .andExpect(jsonPath("$.basePath").value("/repos"));
    }

    @Test void connectionRoutesPreserveTheirContracts() throws Exception {
        String settings = """
                {"host":"example.test","port":22,"username":"operator","password":"test-only",
                 "basePath":"/repos","sudoEnabled":false,"sudoUsername":"git","sudoPassword":"",
                 "cloneUsername":"git","group":"","trustToken":"test-token",
                 "keyConfirmed":true,"replaceChangedKey":false}
                """;
        when(connections.defaults()).thenReturn(java.util.Map.of("saved", false));
        when(connections.check(any())).thenReturn(java.util.Map.of("ok", true));
        when(connections.discover("example.test", 22)).thenReturn(java.util.Map.of("token", "test-token"));
        mvc.perform(get("/api/connection")).andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(false));
        mvc.perform(post("/api/connection/check").contentType("application/json")
                .content(settings))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        mvc.perform(post("/api/connection/observe-server-key").contentType("application/json")
                .content("{\"host\":\"example.test\",\"port\":22}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").value("test-token"));
        verify(connections, never()).save(any());
        mvc.perform(put("/api/connection").contentType("application/json")
                .content(settings))
                .andExpect(status().isOk()).andExpect(jsonPath("$.saved").value(true));
        verify(connections).save(argThat(saved -> saved.host().equals("example.test")));
    }

    @Test void malformedConnectionDoesNotReportProjectNameErrorOrEchoInput() throws Exception {
        mvc.perform(post("/api/connection/check").contentType("application/json")
                .content("{\"port\":\"invalid-test-only\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("요청 항목의 형식과 필수값을 확인하세요."));
        verify(connections, never()).check(any());
    }

    @Test void createsRepositoryAndReturnsConnectionAddress() throws Exception {
        mvc.perform(post("/api/projects").contentType("application/json").content("{\"name\":\"Sample-api\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cloneUrl").value("ssh://git@git.example.test:22/srv/git/Sample-api.git"));
        verify(provisioner).create(eq("Sample-api"), any());
    }

    @Test void rejectsPathTraversalBeforeProvisioning() throws Exception {
        mvc.perform(post("/api/projects").contentType("application/json").content("{\"name\":\"../other\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(provisioner);
    }

    @Test void savedSettingsUseTheSameProvisionerAndResponseContract() throws Exception {
        var selected = new GitConnection(new GitServerProperties("saved.example.test", 2222,
                "operator", "test-only", "/repos", "", 15, true, "git", "", "developer"), "test-key", "");
        when(connections.selected()).thenReturn(selected);
        mvc.perform(post("/api/projects").contentType("application/json").content("{\"name\":\"sample\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.repositoryPath").value("/repos/sample.git"))
                .andExpect(jsonPath("$.cloneUrl").value("ssh://developer@saved.example.test:2222/repos/sample.git"));
        verify(provisioner).create("sample", selected);
    }

    @Test void duplicateIsConflict() throws Exception {
        doThrow(new ProvisioningException(HttpStatus.CONFLICT, "이미 존재합니다.")).when(provisioner).create(eq("existing"), any());
        mvc.perform(post("/api/projects").contentType("application/json").content("{\"name\":\"existing\"}"))
                .andExpect(status().isConflict());
    }

    @Test void rejectsForeignBrowserOrigin() throws Exception {
        mvc.perform(post("/api/projects").header("Origin", "https://foreign.example")
                .contentType("application/json").content("{\"name\":\"sample\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(provisioner);
    }

    @Test void rejectsRebindingHost() throws Exception {
        mvc.perform(post("/api/projects").with(request -> {request.setServerName("foreign.example"); return request;})
                .contentType("application/json").content("{\"name\":\"sample\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(provisioner);
    }

    @Test void pageRenders() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(view().name("index"));
    }
}
