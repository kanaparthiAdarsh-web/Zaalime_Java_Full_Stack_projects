package com.zaalime.rag.answer;

public class GenerationException extends RuntimeException {

    public GenerationException(String message) {
        super(message);
    }

    public GenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
