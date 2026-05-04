package com.bootlens.client.registration;

record RegistrationCallResult(
    boolean success,
    boolean notFound,
    int statusCode,
    String message
) {

    static RegistrationCallResult success(int statusCode) {
        return new RegistrationCallResult(true, false, statusCode, null);
    }

    static RegistrationCallResult notFound(String message) {
        return new RegistrationCallResult(false, true, 404, message);
    }

    static RegistrationCallResult failure(int statusCode, String message) {
        return new RegistrationCallResult(false, false, statusCode, message);
    }
}
