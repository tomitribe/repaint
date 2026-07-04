package org.tomitribe.repaint.six;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The whole native layer: three libc functions, called directly from Java
 * via FFM. No JNI, no bundled .dylib/.so, no forked processes — the
 * linker binds the symbols out of the C library the JVM already has
 * loaded. This is the "minimum viable native layer" the survey concluded
 * with (docs/SURVEY.md); JLine's CLibrary and FfmSignalHandler are the
 * full-fat blueprints, including the per-OS variations we simplify here.
 */
public final class Libc {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIBC = LINKER.defaultLookup();

    // int isatty(int fd)
    private static final MethodHandle ISATTY = LINKER.downcallHandle(
            LIBC.find("isatty").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

    // int ioctl(int fd, unsigned long request, ...) — variadic from arg 2
    private static final MethodHandle IOCTL = LINKER.downcallHandle(
            LIBC.find("ioctl").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS),
            Linker.Option.firstVariadicArg(2));

    // int sigaction(int sig, const struct sigaction *act, struct sigaction *oact)
    private static final MethodHandle SIGACTION = LINKER.downcallHandle(
            LIBC.find("sigaction").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    private static final boolean LINUX = System.getProperty("os.name").startsWith("Linux");

    /** The ioctl request code for "get window size" — a different magic number per OS family. */
    private static final long TIOCGWINSZ = LINUX ? 0x5413L : 0x40087468L; // mac/BSD share the BSD encoding

    /** Window-change signal. 28 on both macOS and Linux (not everywhere — see JLine's per-OS table). */
    private static final int SIGWINCH = 28;

    /** Restart interrupted syscalls instead of failing them with EINTR. */
    private static final int SA_RESTART = LINUX ? 0x10000000 : 0x0002;

    // struct winsize { unsigned short ws_row, ws_col, ws_xpixel, ws_ypixel; }
    private static final MemoryLayout WINSIZE = MemoryLayout.structLayout(
            ValueLayout.JAVA_SHORT.withName("ws_row"),
            ValueLayout.JAVA_SHORT.withName("ws_col"),
            ValueLayout.JAVA_SHORT.withName("ws_xpixel"),
            ValueLayout.JAVA_SHORT.withName("ws_ypixel"));

    private Libc() {
    }

    /** The real thing, at last: chapter 03 forked a shell to ask this question. */
    public static boolean isatty(final int fd) {
        try {
            return (int) ISATTY.invoke(fd) == 1;
        } catch (final Throwable e) {
            throw new IllegalStateException("isatty(" + fd + ") failed", e);
        }
    }

    /**
     * Window size of the terminal on EXACTLY this fd — the discipline the
     * survey demanded ("measure the fd you draw on"; compose sizes stdout
     * while painting stderr and collapses to 80x24 when stdout is
     * redirected). Returns {rows, cols}, or null if fd isn't a terminal.
     */
    public static int[] winsize(final int fd) {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment ws = arena.allocate(WINSIZE);
            final int rc = (int) IOCTL.invoke(fd, TIOCGWINSZ, ws);
            if (rc != 0) {
                return null;
            }
            return new int[]{
                    Short.toUnsignedInt(ws.get(ValueLayout.JAVA_SHORT, 0)),
                    Short.toUnsignedInt(ws.get(ValueLayout.JAVA_SHORT, 2)),
            };
        } catch (final Throwable e) {
            throw new IllegalStateException("ioctl(TIOCGWINSZ) failed", e);
        }
    }

    // -- SIGWINCH ---------------------------------------------------------

    private static final AtomicBoolean WINCH = new AtomicBoolean();

    /**
     * The native signal handler. It runs in SIGNAL CONTEXT, where almost
     * nothing is safe to do — so it does the one safe thing: a single
     * atomic store. All real work happens on the dispatcher thread below.
     * (Same design as JLine's FfmSignalHandler, same documented caveat:
     * an FFM upcall is not formally async-signal-safe; keeping it to one
     * store is the mitigation.)
     */
    static void winch(final int signal) {
        WINCH.set(true);
    }

    /**
     * Install a SIGWINCH handler: resize becomes a push. The callback runs
     * on a daemon dispatcher thread, never in signal context.
     */
    public static void onWinch(final Runnable callback) {
        try {
            final MethodHandle handler = MethodHandles.lookup().findStatic(
                    Libc.class, "winch", MethodType.methodType(void.class, int.class));
            final MemorySegment stub = LINKER.upcallStub(
                    handler, FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT), Arena.global());

            // struct sigaction, simplified to the two fields we set.
            // macOS: { handler*, int mask, int flags } — 16 bytes.
            // Linux (glibc): { handler*, sigset_t mask (128 bytes), int flags, restorer* }.
            final MemoryLayout layout = LINUX
                    ? MemoryLayout.structLayout(
                            ValueLayout.ADDRESS.withName("handler"),
                            MemoryLayout.sequenceLayout(16, ValueLayout.JAVA_LONG).withName("mask"),
                            ValueLayout.JAVA_INT.withName("flags"),
                            MemoryLayout.paddingLayout(4),
                            ValueLayout.ADDRESS.withName("restorer"))
                    : MemoryLayout.structLayout(
                            ValueLayout.ADDRESS.withName("handler"),
                            ValueLayout.JAVA_INT.withName("mask"),
                            ValueLayout.JAVA_INT.withName("flags"));

            final Arena arena = Arena.global(); // the handler must outlive us
            final MemorySegment act = arena.allocate(layout);
            act.set(ValueLayout.ADDRESS, 0, stub);
            act.set(ValueLayout.JAVA_INT,
                    layout.byteOffset(MemoryLayout.PathElement.groupElement("flags")), SA_RESTART);

            final int rc = (int) SIGACTION.invoke(SIGWINCH, act, MemorySegment.NULL);
            if (rc != 0) {
                throw new IllegalStateException("sigaction(SIGWINCH) returned " + rc);
            }
        } catch (final Throwable e) {
            throw new IllegalStateException("installing SIGWINCH handler failed", e);
        }

        final Thread dispatcher = new Thread(() -> {
            while (true) {
                if (WINCH.getAndSet(false)) {
                    callback.run();
                }
                try {
                    Thread.sleep(25);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "repaint-sigwinch");
        dispatcher.setDaemon(true);
        dispatcher.start();
    }
}
