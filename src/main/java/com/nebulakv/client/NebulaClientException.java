package com.nebulakv.client;

import java.io.IOException;

public class NebulaClientException extends IOException {

    public NebulaClientException(String message) {
        super(message);
    }

    public NebulaClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
