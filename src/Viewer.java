import com.sun.jna.LastErrorException;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;

import java.io.IOException;
import java.util.Arrays;

/**
 * @author Domenic
 * @Classname Viewer
 * @Description Main Class <a href="https://en.wikipedia.org/wiki/ANSI_escape_code#CSI_(Control_Sequence_Introducer)_sequences">ANSI_escape_code</a>
 * @Date 3/20/2023 4:54 PM
 * @Created by Domenic
 */
public class Viewer {

    private static final Terminal terminal =
            Platform.isWindows() ? new WindowsTerminal() :
                    Platform.isMac() ? new MacOsTerminal() :
                            new UnixTerminal();

    public static void main(String[] args) throws IOException {

        initEditor();
        refreshScreen();

        while (true) {
            int key = readkey();
            handlekey(key);
            System.out.print((char) key + " (" + key + ")\r\n");
        }
    }

    private static void initEditor() {
        System.out.println("---- " + terminal.getClass().getName() + " ----");
        // enable terminal raw mode
        terminal.enableRawMode();
        terminal.getWindowSize();
    }

    private static void exitEditor() {
        System.out.print("\033[2J");
        System.out.print("\033[H");
        terminal.disableRawMode();
        System.exit(0);
    }

    /**
     * Refresh screen and print something (just like Vim)
     */
    private static void refreshScreen() {
        StringBuilder builder = new StringBuilder();

        builder.append("\033[2J");
        builder.append("\033[H");

        builder.append("~\r\n".repeat(Math.max(0, WindowSize.rowNum - 1)));

        String statusMessage = "Domenic Zhang's Editor - v0.0.1";
        builder.append("\033[7m")
                .append(statusMessage)
                .append(" ".repeat(Math.max(0, WindowSize.colNum - statusMessage.length())))
                .append("\033[0m");

        System.out.println(builder);
    }

    private static int readkey() throws IOException {
        return System.in.read();
    }

    private static void handlekey(int key) {
        // press 'q' to exit
        if (key == 'q') {
            // disable raw mode after exit
            exitEditor();
        }
    }

}


/**
 * Terminal Windows Dimensions (Column and Row)
 */
final class WindowSize {

    public static int rowNum;
    public static int colNum;

    public static void setWindowSize(int rowNum, int colNum) {
        WindowSize.rowNum = rowNum;
        WindowSize.colNum = colNum;
    }
}


/* ========== Different Operating System Support ========== */

interface Terminal {
    void enableRawMode();

    void disableRawMode();

    void getWindowSize();
}

/**
 * Unix Compatible
 */
class UnixTerminal implements Terminal {

    private static LibC.Termios originalAttributes;

    @Override
    public void enableRawMode() {
        LibC.Termios termios = new LibC.Termios();
        int rc = LibC.INSTANCE.tcgetattr(LibC.SYSTEM_OUT_FD, termios);

        if (rc != 0) {
            System.err.println("There was a problem calling tcgetattr");
            System.exit(rc);
        }

        originalAttributes = LibC.Termios.of(termios);

        termios.c_lflag &= ~(LibC.ECHO | LibC.ICANON | LibC.IEXTEN | LibC.ISIG);
        termios.c_iflag &= ~(LibC.IXON | LibC.ICRNL);
        termios.c_oflag &= ~(LibC.OPOST);

        /* termios.c_cc[LibC.VMIN] = 0;
        termios.c_cc[LibC.VTIME] = 1; */

        LibC.INSTANCE.tcsetattr(LibC.SYSTEM_OUT_FD, LibC.TCSAFLUSH, termios);
    }

    @Override
    public void disableRawMode() {
        LibC.INSTANCE.tcsetattr(LibC.SYSTEM_OUT_FD, LibC.TCSAFLUSH, originalAttributes);
    }

    @Override
    public void getWindowSize() {
        final LibC.Winsize winsize = new LibC.Winsize();

        final int rc = LibC.INSTANCE.ioctl(LibC.SYSTEM_OUT_FD, LibC.INSTANCE.TIOCGWINSZ, winsize);

        if (rc != 0) {
            System.err.println("ioctl failed with return code[={}]" + rc);
            System.exit(1);
        }

        WindowSize.setWindowSize(winsize.ws_row, winsize.ws_col);
    }

    /**
     * Extends com.sun.jna.Library Interface
     */
    interface LibC extends Library {

        int SYSTEM_OUT_FD = 0;
        int ISIG = 1, ICANON = 2, ECHO = 10, TCSAFLUSH = 2,
                IXON = 2000, ICRNL = 400, IEXTEN = 100000, OPOST = 1, VMIN = 6, VTIME = 5, TIOCGWINSZ = 0x5413;

        // we're loading the C standard library for POSIX systems
        LibC INSTANCE = Native.load("c", LibC.class);

        @Structure.FieldOrder(value = {"ws_row", "ws_col", "ws_xpixel", "ws_ypixel"})
        class Winsize extends Structure {
            public short ws_row, ws_col, ws_xpixel, ws_ypixel;
        }


        @Structure.FieldOrder(value = {"c_iflag", "c_oflag", "c_cflag", "c_lflag", "c_cc"})
        class Termios extends Structure {
            public int c_iflag, c_oflag, c_cflag, c_lflag;

            public byte[] c_cc = new byte[32];

            public Termios() {
            }

            public static Termios of(Termios t) {
                Termios copy = new Termios();
                copy.c_iflag = t.c_iflag;
                copy.c_oflag = t.c_oflag;
                copy.c_cflag = t.c_cflag;
                copy.c_lflag = t.c_lflag;
                copy.c_cc = t.c_cc.clone();
                return copy;
            }

            @Override
            public String toString() {
                return "Termios{" +
                        "c_iflag=" + c_iflag +
                        ", c_oflag=" + c_oflag +
                        ", c_cflag=" + c_cflag +
                        ", c_lflag=" + c_lflag +
                        ", c_cc=" + Arrays.toString(c_cc) +
                        '}';
            }
        }

        int tcgetattr(int fd, Termios termios);

        int tcsetattr(int fd, int optional_actions, Termios termios);

        int ioctl(int fd, int opt, Winsize winsize) throws LastErrorException;

    }
}

/**
 * MacOs Compatible
 */
class MacOsTerminal implements Terminal {

    private static LibC.Termios originalAttributes;

    @Override
    public void enableRawMode() {
        LibC.Termios termios = new LibC.Termios();
        int rc = LibC.INSTANCE.tcgetattr(LibC.SYSTEM_OUT_FD, termios);

        if (rc != 0) {
            System.err.println("There was a problem calling tcgetattr");
            System.exit(rc);
        }

        originalAttributes = LibC.Termios.of(termios);

        termios.c_lflag &= ~(LibC.ECHO | LibC.ICANON | LibC.IEXTEN | LibC.ISIG);
        termios.c_iflag &= ~(LibC.IXON | LibC.ICRNL);
        termios.c_oflag &= ~(LibC.OPOST);

        /* termios.c_cc[LibC.VMIN] = 0;
        termios.c_cc[LibC.VTIME] = 1; */

        LibC.INSTANCE.tcsetattr(LibC.SYSTEM_OUT_FD, LibC.TCSAFLUSH, termios);
    }

    @Override
    public void disableRawMode() {
        LibC.INSTANCE.tcsetattr(LibC.SYSTEM_OUT_FD, LibC.TCSAFLUSH, originalAttributes);
    }

    @Override
    public void getWindowSize() {
        final LibC.Winsize winsize = new LibC.Winsize();

        final int rc = LibC.INSTANCE.ioctl(LibC.SYSTEM_OUT_FD, LibC.INSTANCE.TIOCGWINSZ, winsize);

        if (rc != 0) {
            System.err.println("ioctl failed with return code[={}]" + rc);
            System.exit(1);
        }

        WindowSize.setWindowSize(winsize.ws_row, winsize.ws_col);
    }

    /**
     * Extends com.sun.jna.Library Interface
     */
    interface LibC extends Library {

        int SYSTEM_OUT_FD = 0;
        int ISIG = 1, ICANON = 2, ECHO = 10, TCSAFLUSH = 2,
                IXON = 2000, ICRNL = 400, IEXTEN = 100000, OPOST = 1, VMIN = 6, VTIME = 5, TIOCGWINSZ = 0x40087468;

        // we're loading the C standard library for POSIX systems
        LibC INSTANCE = Native.load("c", LibC.class);

        @Structure.FieldOrder(value = {"ws_row", "ws_col", "ws_xpixel", "ws_ypixel"})
        class Winsize extends Structure {
            public short ws_row, ws_col, ws_xpixel, ws_ypixel;
        }


        @Structure.FieldOrder(value = {"c_iflag", "c_oflag", "c_cflag", "c_lflag", "c_cc"})
        class Termios extends Structure {
            public long c_iflag, c_oflag, c_cflag, c_lflag;

            public byte[] c_cc = new byte[19];

            public Termios() {
            }

            public static Termios of(Termios t) {
                Termios copy = new Termios();
                copy.c_iflag = t.c_iflag;
                copy.c_oflag = t.c_oflag;
                copy.c_cflag = t.c_cflag;
                copy.c_lflag = t.c_lflag;
                copy.c_cc = t.c_cc.clone();
                return copy;
            }

            @Override
            public String toString() {
                return "Termios{" +
                        "c_iflag=" + c_iflag +
                        ", c_oflag=" + c_oflag +
                        ", c_cflag=" + c_cflag +
                        ", c_lflag=" + c_lflag +
                        ", c_cc=" + Arrays.toString(c_cc) +
                        '}';
            }
        }

        int tcgetattr(int fd, Termios termios);

        int tcsetattr(int fd, int optional_actions, Termios termios);

        int ioctl(int fd, int opt, Winsize winsize) throws LastErrorException;

    }

}

/**
 * Windows Compatible
 */
class WindowsTerminal implements Terminal {

    private IntByReference inMode;
    private IntByReference outMode;

    @Override
    public void enableRawMode() {
        Pointer inHandle = Kernel32.INSTANCE.GetStdHandle(Kernel32.STD_INPUT_HANDLE);

        inMode = new IntByReference();
        Kernel32.INSTANCE.GetConsoleMode(inHandle, inMode);

        int inMode;
        inMode = this.inMode.getValue() & ~(
                Kernel32.ENABLE_ECHO_INPUT
                        | Kernel32.ENABLE_LINE_INPUT
                        | Kernel32.ENABLE_MOUSE_INPUT
                        | Kernel32.ENABLE_WINDOW_INPUT
                        | Kernel32.ENABLE_PROCESSED_INPUT
        );

        inMode |= Kernel32.ENABLE_VIRTUAL_TERMINAL_INPUT;


        Kernel32.INSTANCE.SetConsoleMode(inHandle, inMode);

        Pointer outHandle = Kernel32.INSTANCE.GetStdHandle(Kernel32.STD_OUTPUT_HANDLE);
        outMode = new IntByReference();
        Kernel32.INSTANCE.GetConsoleMode(outHandle, outMode);

        int outMode = this.outMode.getValue();
        outMode |= Kernel32.ENABLE_VIRTUAL_TERMINAL_PROCESSING;
        outMode |= Kernel32.ENABLE_PROCESSED_OUTPUT;
        Kernel32.INSTANCE.SetConsoleMode(outHandle, outMode);

    }

    @Override
    public void disableRawMode() {
        Pointer inHandle = Kernel32.INSTANCE.GetStdHandle(Kernel32.STD_INPUT_HANDLE);
        Kernel32.INSTANCE.SetConsoleMode(inHandle, inMode.getValue());

        Pointer outHandle = Kernel32.INSTANCE.GetStdHandle(Kernel32.STD_OUTPUT_HANDLE);
        Kernel32.INSTANCE.SetConsoleMode(outHandle, outMode.getValue());
    }

    @Override
    public void getWindowSize() {
        final Kernel32.CONSOLE_SCREEN_BUFFER_INFO info = new Kernel32.CONSOLE_SCREEN_BUFFER_INFO();
        final Kernel32 instance = Kernel32.INSTANCE;
        final Pointer handle = Kernel32.INSTANCE.GetStdHandle(Kernel32.STD_OUTPUT_HANDLE);
        instance.GetConsoleScreenBufferInfo(handle, info);
        WindowSize.setWindowSize(info.windowHeight(), info.windowWidth());
    }

    /**
     * Extends com.sun.jna.win32.StdCallLibrary Interface
     */
    interface Kernel32 extends StdCallLibrary {

        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);

        /**
         * The CryptUIDlgSelectCertificateFromStore function displays a dialog box
         * that allows the selection of a certificate from a specified store.
         *
         * @param hCertStore Handle of the certificate store to be searched.
         * @param hwnd Handle of the window for the display. If NULL,
         * defaults to the desktop window.
         * @param pwszTitle String used as the title of the dialog box. If
         * NULL, the default title, "Select Certificate,"
         * is used.
         * @param pwszDisplayString Text statement in the selection dialog box. If
         * NULL, the default phrase, "Select a certificate
         * you want to use," is used.
         * @param dwDontUseColumn Flags that can be combined to exclude columns of
         * the display.
         * @param dwFlags Currently not used and should be set to 0.
         * @param pvReserved Reserved for future use.
         * @return Returns a pointer to the selected certificate context. If no
         * certificate was selected, NULL is returned. When you have
         * finished using the certificate, free the certificate context by
         * calling the CertFreeCertificateContext function.
         */
        public static final int ENABLE_VIRTUAL_TERMINAL_PROCESSING = 0x0004, ENABLE_PROCESSED_OUTPUT = 0x0001;

        int ENABLE_LINE_INPUT = 0x0002;
        int ENABLE_PROCESSED_INPUT = 0x0001;
        int ENABLE_ECHO_INPUT = 0x0004;
        int ENABLE_MOUSE_INPUT = 0x0010;
        int ENABLE_WINDOW_INPUT = 0x0008;
        int ENABLE_QUICK_EDIT_MODE = 0x0040;
        int ENABLE_INSERT_MODE = 0x0020;

        int ENABLE_EXTENDED_FLAGS = 0x0080;

        int ENABLE_VIRTUAL_TERMINAL_INPUT = 0x0200;


        int STD_OUTPUT_HANDLE = -11;
        int STD_INPUT_HANDLE = -10;
        int DISABLE_NEWLINE_AUTO_RETURN = 0x0008;

        // BOOL WINAPI GetConsoleScreenBufferInfo(
        // _In_   HANDLE hConsoleOutput,
        // _Out_  PCONSOLE_SCREEN_BUFFER_INFO lpConsoleScreenBufferInfo);
        void GetConsoleScreenBufferInfo(
                Pointer in_hConsoleOutput,
                CONSOLE_SCREEN_BUFFER_INFO out_lpConsoleScreenBufferInfo)
                throws LastErrorException;

        void GetConsoleMode(
                Pointer in_hConsoleOutput,
                IntByReference out_lpMode)
                throws LastErrorException;

        void SetConsoleMode(
                Pointer in_hConsoleOutput,
                int in_dwMode) throws LastErrorException;

        Pointer GetStdHandle(int nStdHandle);

        // typedef struct _CONSOLE_SCREEN_BUFFER_INFO {
        //   COORD      dwSize;
        //   COORD      dwCursorPosition;
        //   WORD       wAttributes;
        //   SMALL_RECT srWindow;
        //   COORD      dwMaximumWindowSize;
        // } CONSOLE_SCREEN_BUFFER_INFO;
        class CONSOLE_SCREEN_BUFFER_INFO extends Structure {


            public COORD dwSize;
            public COORD dwCursorPosition;
            public short wAttributes;
            public SMALL_RECT srWindow;
            public COORD dwMaximumWindowSize;

            private static String[] fieldOrder = {"dwSize", "dwCursorPosition", "wAttributes", "srWindow", "dwMaximumWindowSize"};

            @Override
            protected java.util.List<String> getFieldOrder() {
                return java.util.Arrays.asList(fieldOrder);
            }

            public int windowWidth() {
                return this.srWindow.width() + 1;
            }

            public int windowHeight() {
                return this.srWindow.height() + 1;
            }
        }

        // typedef struct _COORD {
        //    SHORT X;
        //    SHORT Y;
        //  } COORD, *PCOORD;
        class COORD extends Structure implements Structure.ByValue {
            public COORD() {
            }

            public COORD(short X, short Y) {
                this.X = X;
                this.Y = Y;
            }

            public short X;
            public short Y;

            private static String[] fieldOrder = {"X", "Y"};

            @Override
            protected java.util.List<String> getFieldOrder() {
                return java.util.Arrays.asList(fieldOrder);
            }
        }

        // typedef struct _SMALL_RECT {
        //    SHORT Left;
        //    SHORT Top;
        //    SHORT Right;
        //    SHORT Bottom;
        //  } SMALL_RECT;
        class SMALL_RECT extends Structure {
            public SMALL_RECT() {
            }

            public SMALL_RECT(SMALL_RECT org) {
                this(org.Top, org.Left, org.Bottom, org.Right);
            }

            public SMALL_RECT(short Top, short Left, short Bottom, short Right) {
                this.Top = Top;
                this.Left = Left;
                this.Bottom = Bottom;
                this.Right = Right;
            }

            public short Left;
            public short Top;
            public short Right;
            public short Bottom;

            private static String[] fieldOrder = {"Left", "Top", "Right", "Bottom"};

            @Override
            protected java.util.List<String> getFieldOrder() {
                return java.util.Arrays.asList(fieldOrder);
            }

            public short width() {
                return (short) (this.Right - this.Left);
            }

            public short height() {
                return (short) (this.Bottom - this.Top);
            }
        }
    }
}