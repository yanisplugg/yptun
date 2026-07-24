package patchgen;

import com.google.archivepatcher.generator.FileByFileV1DeltaGenerator;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Host-side release tool: generates a gzip-compressed File-by-File v1 binary patch that upgrades
 * {@code oldApk} -> {@code newApk}. Upload the resulting .patch.gz as a GitHub release asset named
 * {@code YPtun-delta-<oldVer>-<newVer>-<abi>.patch.gz}. Also prints the SHA-256 of the new APK.
 *
 * Usage: java patchgen.PatchGen <oldApk> <newApk> <outPatchGz>
 */
public final class PatchGen {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: PatchGen <oldApk> <newApk> <outPatchGz>");
            System.exit(2);
        }
        File oldApk = new File(args[0]);
        File newApk = new File(args[1]);
        File outGz = new File(args[2]);
        try (OutputStream gz = new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(outGz)))) {
            new FileByFileV1DeltaGenerator().generateDelta(oldApk, newApk, gz);
        }
        System.out.println("old apk bytes:  " + oldApk.length());
        System.out.println("new apk bytes:  " + newApk.length());
        System.out.println("patch.gz bytes: " + outGz.length());
        System.out.println("new apk sha256: " + sha256(newApk));
    }

    static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
