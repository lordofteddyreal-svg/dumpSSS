import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.lang.reflect.*;

/**
 * DumpKeys — снимает реальные byte[] из PE-нативок Aurora БЕЗ Minecraft.
 * Запускать ТОЛЬКО на Windows (или Linux+Wine с Windows JDK), на оригинальном (непатченном) jar:
 *   wine /path/to/windows/java.exe -cp .;Aurora-1.21.8.jar DumpKeys Aurora-1.21.8.jar keys.bin
 *
 * Что делает:
 *  1. распаковывает META-INF/aurora-native/*.dll во временную папку
 *  2. System.load(original) + System.load(bridge) вручную (без FabricLoader.getGameDir)
 *  3. вызывает registerAll через reflection, проверяет ==58
 *  4. находит ВСЕ native (I)[B во всем jar, перебирает индексы 0..5000, сохраняет ненулловые в keys.bin
 * keys.bin потом вшивается в v3 патч для Mac.
 */
public class DumpKeys {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: DumpKeys <Aurora-1.21.8.jar.bak(original)> <keys.bin out>");
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

        // грузим Aurora классы из jar
        java.net.URL[] urls = { jarPath.toUri().toURL() };
        try (java.net.URLClassLoader cl = new java.net.URLClassLoader(urls, DumpKeys.class.getClassLoader())) {
            Class<?> nb = Class.forName("aurora.fabric.NativeBootstrap", true, cl);
            Method reg = null;
            for (Method m : nb.getDeclaredMethods()) if (m.getName().equals("registerAll")) reg = m;
            if (reg == null) throw new IllegalStateException("registerAll not found");
            reg.setAccessible(true);
            System.out.println("[dump] calling registerAll...");
            Object r = reg.invoke(null, (Object) cl);
            System.out.println("[dump] registerAll returned: " + r + " (ждем 58)");
            if (!r.equals(58)) System.out.println("[dump] WARNING: не 58, часть методов не зарегистрируется");

            // собираем все (I)[B нативы
            List<String> targets = new ArrayList<>();
            try (ZipFile zf = new ZipFile(jarPath.toFile())) {
                // список классов из patcher-лога + сканируем весь jar через ASM? без ASM - через reflection по известным именам
                // проще: перебираем все .class, грузим через Class.forName и ищем native (I)[B
                Enumeration<? extends ZipEntry> en = zf.entries();
                int total = 0;
                while (en.hasMoreElements()) {
                    String n = en.nextElement().getName();
                    if (!n.endsWith(".class")) continue;
                    if (n.contains("MacFallback") || n.contains("DumpKeys")) continue;
                    String cn = n.substring(0, n.length() - 6).replace('/', '.');
                    total++;
                    Class<?> c;
                    try { c = Class.forName(cn, false, cl); }
                    catch (Throwable t) { continue; }
                    for (Method m : c.getDeclaredMethods()) {
                        if (Modifier.isNative(m.getModifiers())
                            && m.getReturnType().equals(byte[].class)
                            && m.getParameterCount() == 1
                            && m.getParameterTypes()[0].equals(int.class)) {
                            targets.add(cn + "#" + m.getName());
                        }
                    }
                }
                System.out.println("[dump] scanned classes=" + total + " native(I)[B methods=" + targets.size());
            }
            for (String t : targets) System.out.println("  " + t);

            // дампим
            try (java.io.DataOutputStream dos = new java.io.DataOutputStream(Files.newOutputStream(outPath))) {
                dos.writeInt(targets.size());
                for (String t : targets) {
                    String[] parts = t.split("#");
                    Class<?> c = Class.forName(parts[0], true, cl);
                    Method m = null;
                    for (Method mm : c.getDeclaredMethods())
                        if (mm.getName().equals(parts[1]) && mm.getParameterCount() == 1) m = mm;
                    m.setAccessible(true);
                    // пробуем 0..5000, сохраняем только ненулл (первые null подряд 50 штук = стоп)
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
                    // trim trailing nulls
                    while (!found.isEmpty() && found.get(found.size() - 1) == null) found.remove(found.size() - 1);
                    System.out.println("[dump] " + t + " total=" + found.size());
                    dos.writeUTF(t);
                    dos.writeInt(found.size());
                    for (byte[] b : found) {
                        if (b == null) { dos.writeInt(-1); }
                        else { dos.writeInt(b.length); dos.write(b); }
                    }
                }
            }
            System.out.println("[dump] WROTE " + outPath.toAbsolutePath() + " (" + Files.size(outPath) + " bytes)");
            System.out.println("[dump] Перекинь keys.bin на Mac, дальше сделаем v3 патчер.");
        }
    }
}
