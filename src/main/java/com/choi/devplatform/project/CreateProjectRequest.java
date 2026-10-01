package com.choi.devplatform.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateProjectRequest(
        @NotBlank @Size(max = 64)
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]*", message = "영문, 숫자, 하이픈, 밑줄만 사용하고 영문 또는 숫자로 시작하세요.")
        String name) {
}
