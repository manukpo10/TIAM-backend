package com.tiam.exercise.pdf;

/**
 * Thrown when OpenPDF fails to render a ficha.
 * Mapped to 500 Internal Server Error by the global exception handler.
 */
public class PdfGenerationException extends RuntimeException {

    public PdfGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
