package common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Persistencia sencilla en un archivo CSV.
 *
 * Es segura para uso concurrente (los metodos que escriben estan sincronizados),
 * de modo que varios hilos del servidor o del cliente puedan registrar filas
 * sin corromper el archivo.
 */
public final class CsvStore {

    private final Path file;
    private final Object lock = new Object();
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * @param fileName nombre del archivo CSV (se crea si no existe).
     * @param header   cabecera CSV (sin salto de linea).
     */
    public CsvStore(String fileName, String header) {
        this.file = Paths.get(fileName);
        try {
            if (Files.notExists(file)) {
                Files.write(file, (header + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            }
        } catch (IOException e) {
            System.err.println("No se pudo crear el archivo CSV '" + fileName + "': " + e.getMessage());
        }
    }

    /** Marca de tiempo actual con formato legible. */
    public static String now() {
        return LocalDateTime.now().format(TS);
    }

    /** Escapa un campo para CSV (comillas dobles si contiene coma, comillas o salto de linea). */
    public static String esc(String field) {
        if (field == null) {
            return "";
        }
        boolean needsQuotes = field.contains(",") || field.contains("\"")
                || field.contains("\n") || field.contains("\r");
        String out = field.replace("\"", "\"\"");
        return needsQuotes ? "\"" + out + "\"" : out;
    }

    /** Agrega una fila al CSV. Los valores se escapan automaticamente. */
    public void append(String... values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(esc(values[i]));
        }
        sb.append(System.lineSeparator());
        synchronized (lock) {
            try {
                Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                System.err.println("No se pudo escribir en el CSV: " + e.getMessage());
            }
        }
    }

    public String path() {
        return file.toAbsolutePath().toString();
    }
}
