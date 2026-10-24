package net.dvmn2.resourcecontrol.client.util;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Atomic file replacement.
 */
public final class AtomicFiles {

    private AtomicFiles() {
    }

    /**
     * Writes {@code data} to a temp file next to {@code target} and moves it into place.
     */
    public static void writeAtomically(Path target, byte[] data) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temp, data);
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
