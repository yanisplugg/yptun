package patchgen;

import com.google.archivepatcher.applier.FileByFileV1DeltaApplier;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;

/**
 * Host-side counterpart used to VERIFY a generated patch round-trips byte-for-byte (the same code
 * path the app runs via the vendored :archivepatcher applier).
 *
 * Usage: java patchgen.PatchApply <oldApk> <patchGz> <outApk>
 */
public final class PatchApply {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: PatchApply <oldApk> <patchGz> <outApk>");
            System.exit(2);
        }
        File oldApk = new File(args[0]);
        File patchGz = new File(args[1]);
        File outApk = new File(args[2]);
        File tmp = new File(System.getProperty("java.io.tmpdir"), "patchapply-tmp");
        tmp.mkdirs();
        try (InputStream patchIn = new GZIPInputStream(new BufferedInputStream(new java.io.FileInputStream(patchGz)));
             OutputStream out = new FileOutputStream(outApk)) {
            new FileByFileV1DeltaApplier(tmp).applyDelta(oldApk, patchIn, out);
        }
        System.out.println("reconstructed bytes: " + outApk.length());
        System.out.println("reconstructed sha256: " + PatchGen.sha256(outApk));
    }
}
