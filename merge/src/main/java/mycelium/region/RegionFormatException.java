package mycelium.region;

import java.io.IOException;

/** A region file is damaged as a whole, so none of its slots can be trusted. */
public class RegionFormatException extends IOException {

    private static final long serialVersionUID = 1L;

    public RegionFormatException(String message) {
        super(message);
    }
}
