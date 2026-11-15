package mycelium.nbt;

import java.io.IOException;

/** The bytes are not valid NBT. */
public class NbtFormatException extends IOException {

    private static final long serialVersionUID = 1L;

    public NbtFormatException(String message) {
        super(message);
    }

    public NbtFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
