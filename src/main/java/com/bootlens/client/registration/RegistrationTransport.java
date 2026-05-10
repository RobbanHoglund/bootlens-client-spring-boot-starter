package com.bootlens.client.registration;

import java.io.IOException;

interface RegistrationTransport {

    RegistrationCallResult post(String url, String jsonBody, String authorizationHeader) throws IOException, InterruptedException;

    RegistrationCallResult delete(String url, String authorizationHeader) throws IOException, InterruptedException;
}
