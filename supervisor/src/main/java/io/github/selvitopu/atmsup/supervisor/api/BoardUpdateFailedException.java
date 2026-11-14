package io.github.selvitopu.atmsup.supervisor.api;

/** The board writer could not store an input's events; the API answers 503. */
class BoardUpdateFailedException extends RuntimeException {

    BoardUpdateFailedException(String message) {
        super(message);
    }

    BoardUpdateFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
