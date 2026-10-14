package com.tiam.pack.service;

/**
 * Why a checkout could not start. The {@link #code()} travels in the {@code ?error=} query of the
 * redirect back to the landing page, so these strings are a contract with the frontend.
 */
public enum PackCheckoutError {
    CERRADO("cerrado"),
    EMAIL("email"),
    NOMBRE("nombre"),
    EDICION("edicion"),
    EXTRAS("extras"),
    TELEFONO("telefono"),
    PRECIO("precio"),
    MESES("meses"),
    LIMITE("limite"),
    PAGO("pago"),
    ORIGEN("origen"),
    INTERNO("interno");

    private final String code;

    PackCheckoutError(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
