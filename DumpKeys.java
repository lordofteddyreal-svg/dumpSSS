import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.lang.reflect.*;

public class DumpKeys {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: DumpKeys <Aurora-orig.jar> <keys.bin out>");
            System.exit(1);
        }
        Path jarPath = Paths.get(args[0]);
        Path outPath = Paths.get(args[1]);
        System.out.println("[dump] jar=" + jarPath.toAbsolutePath());

        Path tmp = Files.createTempDirectory("aurora-dump");
        System.out.println("[dump] tmp=" + tmp);
        String origName = null, bridgeName = null;
        try (ZipFile zf = new ZipFile(jarPath.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.getName().startsWith("META-INF/aurora-native/") && e.getName().endsWith(".dll")) {
                    Path p = tmp.resolve(Paths.get(e.getName()).getFileName().toString());
                    Files.write(p, zf.getInputStream(e).readAllBytes());
                    System.out.println("[dump] extracted " + e.getName() + " -> " + p + " (" + Files.size(p) + ")");
                    if (e.getName().contains("original")) origName = p.toString();
                    if (e.getName().contains("bridge")) bridgeName = p.toString();
                }
            }
        }
        if (origName == null || bridgeName == null) throw new IllegalStateException("dll not found in jar");
        System.out.println("[dump] System.load original...");
        System.load(origName);
        System.out.println("[dump] System.load bridge...");
        System.load(bridgeName);

        List<java.net.URL> _u = new ArrayList<>();
        _u.add(jarPath.toUri().toURL());
        for (String _f : new String[]{"fabric-loader.jar"}) {
            Path _p = Paths.get(_f);
            if (Files.exists(_p)) { _u.add(_p.toUri().toURL()); System.out.println("[dump] +classpath " + _p.toAbsolutePath()); }
        }
        java.net.URL[] urls = _u.toArray(new java.net.URL[0]);
        try (java.net.URLClassLoader cl = new java.net.URLClassLoader(urls, DumpKeys.class.getClassLoader())) {
            Class<?> nb = Class.forName("aurora.fabric.NativeBootstrap", true, cl);
            Method reg = null;
            for (Method m : nb.getDeclaredMethods()) if (m.getName().equals("registerAll")) reg = m;
            if (reg == null) throw new IllegalStateException("registerAll not found");
            reg.setAccessible(true);
            System.out.println("[dump] calling registerAll...");
            Object r = reg.invoke(null, (Object) cl);
            System.out.println("[dump] registerAll returned: " + r + " (ждем 58)");

            List<String> targets = new ArrayList<>();
            try (ZipFile zf = new ZipFile(jarPath.toFile())) {
                Enumeration<? extends ZipEntry> en = zf.entries();
                int total = 0;
                while (en.hasMoreElements()) {
                    String n = en.nextElement().getName();
                    if (!n.endsWith(".class")) continue;
                    if (n.contains("MacFallback") || n.contains("DumpKeys")) continue;
                    String cn = n.substring(0, n.length() - 6).replace('/', '.');
                    total++;
                    try {
                        Class<?> c = Class.forName(cn, false, cl);
                        for (Method m : c.getDeclaredMethods()) {
                            try {
                                if (Modifier.isNative(m.getModifiers())
                                    && m.getReturnType().equals(byte[].class)
                                    && m.getParameterCount() == 1
                                    && m.getParameterTypes()[0].equals(int.class)) {
                                    targets.add(cn + "#" + m.getName());
                                }
                            } catch (Throwable t) { continue; }
                        }
                    } catch (Throwable t) { continue; }
                }
                System.out.println("[dump] scanned classes=" + total + " native(I)[B methods=" + targets.size());
            }
            for (String t : targets) System.out.println("  " + t);

            try (java.io.DataOutputStream dos = new java.io.DataOutputStream(Files.newOutputStream(outPath))) {
                dos.writeInt(targets.size());
                for (String t : targets) {
                    try {
                        String[] parts = t.split("#");
                        Class<?> c = Class.forName(parts[0], true, cl);
                        Method m = null;
                        for (Method mm : c.getDeclaredMethods())
                            if (mm.getName().equals(parts[1]) && mm.getParameterCount() == 1) m = mm;
                        if (m == null) { dos.writeUTF(t); dos.writeInt(0); continue; }
                        m.setAccessible(true);
                        List<byte[]> found = new ArrayList<>();
                        int nullStreak = 0;
                        for (int i = 0; i <= 5000; i++) {
                            byte[] v;
                            try { v = (byte[]) m.invoke(null, i); }
                            catch (Throwable th) { v = null; }
                            if (v == null) {
                                nullStreak++;
                                if (nullStreak >= 50 && found.size() > 10) break;
                                found.add(null);
                            } else {
                                nullStreak = 0;
                                found.add(v.clone());
                            }
                            if (i % 500 == 0) System.out.println("[dump] " + t + " i=" + i + " got=" + (v == null ? "null" : v.length + "b"));
                        }
                        while (!found.isEmpty() && found.get(found.size() - 1) == null) found.remove(found.size() - 1);
                        System.out.println("[dump] " + t + " total=" + found.size());
                        dos.writeUTF(t);
                        dos.writeInt(found.size());
                        for (byte[] b : found) {
                            if (b == null) { dos.writeInt(-1); }
                            else { dos.writeInt(b.length); dos.write(b); }
                        }
                    } catch (Throwable t2) {
                        System.out.println("[dump] SKIP " + t + ": " + t2);
                        dos.writeUTF(t);
                        dos.writeInt(0);
                    }
                }
            }
            System.out.println("[dump] WROTE " + outPath.toAbsolutePath() + " (" + Files.size(outPath) + " bytes)");
        }
    }
}
