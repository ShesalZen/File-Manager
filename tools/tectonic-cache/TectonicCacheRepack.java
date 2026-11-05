import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZInputStream;
import org.tukaani.xz.XZOutputStream;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Repacks the bundled Tectonic cache so the APK stores a single LZMA2 stream instead of a
 * deflate-compressed zip.
 *
 * The cache was previously shipped as {@code tectonic_cache.zip}. The APK compresses every
 * asset once, so the zip's own deflate stream was already incompressible by the time the APK
 * packed it, and the payload cost 13.6 MB per ABI. Repacking the entries uncompressed and
 * compressing the whole archive with LZMA2 lets the compressor see across files and brings the
 * payload down to ~10.0 MB with byte-identical contents.
 *
 * Runtime extraction is XZ -> zip -> files; see {@code TectonicCacheManager}.
 *
 * Usage:
 *   java -cp xz.jar TectonicCacheRepack pack   <source.zip> <output.zip.xz> [preset]
 *   java -cp xz.jar TectonicCacheRepack verify <output.zip.xz> <scratchDir>
 */
public final class TectonicCacheRepack {

    private static final int DEFAULT_PRESET = 6;

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: TectonicCacheRepack <pack|verify> <source> <target> [preset]");
            System.exit(2);
        }
        String mode = args[0];
        if ("pack".equals(mode)) {
            int preset = args.length > 3 ? Integer.parseInt(args[3]) : DEFAULT_PRESET;
            pack(Paths.get(args[1]), Paths.get(args[2]), preset);
        } else if ("verify".equals(mode)) {
            verify(Paths.get(args[1]), Paths.get(args[2]));
        } else {
            System.err.println("Unknown mode: " + mode);
            System.exit(2);
        }
    }

    private static void pack(Path sourceZip, Path outputXz, int preset) throws IOException {
        Path storedZip = Files.createTempFile("tectonic-stored", ".zip");
        try {
            long rawBytes = copyEntriesStored(sourceZip, storedZip);
            Files.deleteIfExists(outputXz);
            try (OutputStream fos = new BufferedOutputStream(Files.newOutputStream(outputXz))) {
                XZOutputStream xz = new XZOutputStream(fos, new LZMA2Options(preset));
                Files.copy(storedZip, xz);
                xz.finish();
            }
            System.out.printf("packed %,d raw bytes -> %,d bytes (xz preset %d)%n",
                    rawBytes, Files.size(outputXz), preset);
        } finally {
            Files.deleteIfExists(storedZip);
        }
    }

    private static void verify(Path xzFile, Path targetDir) throws IOException {
        long total = 0;
        int files = 0;
        try (InputStream fis = Files.newInputStream(xzFile);
             XZInputStream xz = new XZInputStream(fis);
             ZipInputStream zis = new ZipInputStream(xz)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path out = targetDir.resolve(entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                try (OutputStream fos = new BufferedOutputStream(Files.newOutputStream(out))) {
                    total += zis.transferTo(fos);
                }
                files++;
            }
        }
        System.out.printf("verified %d files, %,d bytes%n", files, total);
    }

    private static long copyEntriesStored(Path sourceZip, Path targetZip) throws IOException {
        long total = 0;
        try (ZipFile zf = new ZipFile(sourceZip.toFile());
             ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(targetZip)))) {
            zos.setMethod(ZipOutputStream.STORED);
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry src = entries.nextElement();
                if (src.isDirectory()) {
                    ZipEntry dir = new ZipEntry(src.getName());
                    dir.setMethod(ZipOutputStream.STORED);
                    dir.setSize(0);
                    dir.setCompressedSize(0);
                    dir.setCrc(0);
                    zos.putNextEntry(dir);
                    zos.closeEntry();
                    continue;
                }
                byte[] data;
                try (InputStream in = zf.getInputStream(src)) {
                    data = in.readAllBytes();
                }
                CRC32 crc = new CRC32();
                crc.update(data);
                ZipEntry dst = new ZipEntry(src.getName());
                dst.setMethod(ZipOutputStream.STORED);
                dst.setSize(data.length);
                dst.setCompressedSize(data.length);
                dst.setCrc(crc.getValue());
                dst.setTime(src.getTime());
                zos.putNextEntry(dst);
                zos.write(data);
                zos.closeEntry();
                total += data.length;
            }
        }
        return total;
    }
}
