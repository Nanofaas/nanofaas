package it.unimib.datai.nanofaas.cli.http;

import it.unimib.datai.nanofaas.common.model.InvocationResponse;

public record InvocationCallResult(int httpStatus, InvocationResponse response) {
    public boolean isSuccessful() {
        return httpStatus >= 200 && httpStatus < 300;
    }
}
