package io.github.selvitopu.atmsup.inventory;

/** Thrown when the inventory is invalid; the message lists every problem, one per line. */
public class InventoryException extends RuntimeException {

    public InventoryException(String message) {
        super(message);
    }
}
