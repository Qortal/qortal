package org.qortal.arbitrary;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class LongFileHandler {
    
    /**
     * Writes a long value to the specified file
     * @param file The file to write to
     * @param value The long value to write
     * @throws IOException If an I/O error occurs
     */
    public static void writeLong(File file, long value) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.writeLong(value);
        }
    }
    
    /**
     * Reads a long value from the specified file
     * @param file The file to read from
     * @return The long value read from the file
     * @throws IOException If an I/O error occurs
     */
    public static long readLong(File file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            return raf.readLong();
        }
    }
    
    /**
     * Reads a long value from the specified file with a default value if file doesn't exist
     * @param file The file to read from
     * @param defaultValue The default value to return if file doesn't exist
     * @return The long value from file or defaultValue if file doesn't exist
     * @throws IOException If an I/O error occurs (not including file not found)
     */
    public static long readLongWithDefault(File file, long defaultValue) throws IOException {
        if (!file.exists()) {
            return defaultValue;
        }
        return readLong(file);
    }
    
    /**
     * Creates the file and parent directories if they don't exist
     * @param file The file to create
     * @throws IOException If an I/O error occurs
     */
    public static void createFileIfNotExists(File file) throws IOException {
        Path path = Paths.get(file.getPath());
        if (!Files.exists(path)) {
            Files.createDirectories(path.getParent());
            Files.createFile(path);
        }
    }
}