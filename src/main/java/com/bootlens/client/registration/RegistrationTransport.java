package com.bootlens.client.registration;

import java.io.IOException;

interface RegistrationTransport {

    RegistrationCallResult post(String url, String jsonBody) throws IOException, InterruptedException;

    RegistrationCallResult delete(String url) throws IOException, InterruptedException;
}
