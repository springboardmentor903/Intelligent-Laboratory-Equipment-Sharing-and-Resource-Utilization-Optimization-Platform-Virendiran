package com.labresource.platform.auth;

public final class AuthRequests {
    private AuthRequests() { }

    public static final class Register {
        private String name;
        private String email;
        private String password;
        private Long institutionId;
        private String role;

        public Register() { }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public Long getInstitutionId() { return institutionId; }
        public void setInstitutionId(Long institutionId) { this.institutionId = institutionId; }
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
    }

    public static final class Login {
        private String email;
        private String password;

        public Login() { }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    public static final class AdminLogin {
        private String email;
        private String password;

        public AdminLogin() { }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    public static final class UserResponse {
        private User user;
        public UserResponse() { }
        public UserResponse(User user) { this.user = user; }
        public User getUser() { return user; }
        public void setUser(User user) { this.user = user; }
    }

    public static final class RegistrationResponse {
        private User user;
        private String status;
        private String message;
        public RegistrationResponse() { }
        public RegistrationResponse(User user, String status, String message) {
            this.user = user;
            this.status = status;
            this.message = message;
        }
        public User getUser() { return user; }
        public void setUser(User user) { this.user = user; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }

    public static final class ErrorResponse {
        private String error;
        public ErrorResponse() { }
        public ErrorResponse(String error) { this.error = error; }
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
    }
}

