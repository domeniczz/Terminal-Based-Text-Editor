import com.sun.jna.LastErrorException;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author Domenic
 * @Classname Viewer
 * @Description Main Class <a href="https://en.wikipedia.org/wiki/ANSI_escape_code#CSI_(Control_Sequence_Introducer)_sequences">ANSI_escape_code</a>
 * @Date 3/20/2023 4:54 PM
 * @Created by Domenic
 */
public class Viewer {

    /**
     * Detect the OS type and choose different compatibility code
     */
    private static final Terminal terminal =
            Platform.isWindows() ? new WindowsTerminal() :
                    Platform.isMac() ? new MacOsTerminal() :
                            new UnixTerminal();

    /**
     * Custom key mapping
     */
    private static final int
            ARROW_UP = 1000,
            ARROW_DOWN = 1001,
            ARROW_LEFT = 1002,
            ARROW_RIGHT = 1003,
            HOME = 1004,
            END = 1005,
            PAGE_UP = 1006,
            PAGE_DOWN = 1007,
            DEL = 1008;

    public static void main(String[] args) {

        openFile(args);
        initEditor();
        // GUI.refreshScreen();

        while (true) {
            GUI.scroll();
            GUI.refreshScreen();
            GUI.drawCursor();
            int key = readkey();
            handlekey(key);
        }
    }

    /**
     * Open file specified in arg (only one file)
     * @param args absolute path of the file
     */
    private static void openFile(String[] args) {
        if (args.length == 1) {
            String fileName = args[0];
            Path path = Path.of(fileName);
            if (Files.exists(path)) {
                try (Stream<String> stream = Files.lines(path)) {
                    // load file content
                    GUI.setContent(stream.collect(Collectors.toUnmodifiableList()));
                } catch (IOException e) {
                    System.out.println("\033[31mFile Open Error!\033[0m");
                    System.exit(1);
                }
            }
        }
    }

    private static void initEditor() {
        // enable terminal raw mode
        terminal.enableRawMode();
        terminal.getWindowSize();

        // Print a prompt, indicating what operating system the terminal is running on
        String className = terminal.getClass().getName();
        int len = WindowSize.colNum >= 4 ? (WindowSize.colNum - className.length() - 2) / 2 : 0;
        System.out.println("-".repeat(len) + " " + className + " " + "-".repeat(len));
    }

    private static void exitEditor() {
        GUI.exitEditor();
        terminal.disableRawMode();
        System.exit(0);
    }

    /**
     * Read input key
     *
     * @return key mapped number
     */
    private static int readkey() {
        try {
            int key = System.in.read();
            // ignore escape key '\033'
            if (key != '\033') {
                return key;
            }

            int secondKey = System.in.read();
            if (secondKey != '[' && secondKey != 'O') {
                return secondKey;
            }

            int thirdKey = System.in.read();
            // e.g. arrow_up is "esc[A", arrow_down is "esc[B"
            if (secondKey == '[') {
                if ('A' == thirdKey) {
                    return ARROW_UP;
                }
                if ('B' == thirdKey) {
                    return ARROW_DOWN;
                }
                if ('C' == thirdKey) {
                    return ARROW_RIGHT;
                }
                if ('D' == thirdKey) {
                    return ARROW_LEFT;
                }
                if ('H' == thirdKey) {
                    return HOME;
                }
                if ('F' == thirdKey) {
                    return END;
                }
                // e.g. page_up is "esc[5~"
                if (List.of('0', '1', '2', '3', '4', '5', '6', '7', '8', '9').contains((char) thirdKey)) {
                    int fourthKey = System.in.read();
                    if (fourthKey != '~') {
                        return fourthKey;
                    }
                    switch (fourthKey) {
                        case '3':
                            return DEL;
                        case '5':
                            return PAGE_UP;
                        case '6':
                            return PAGE_DOWN;
                        case '1':
                        case '7':
                            return HOME;
                        case '4':
                        case '8':
                            return END;
                        default:
                            return thirdKey;
                    }
                }
                return thirdKey;
            } else {
                switch (thirdKey) {
                    // there are a multiple "HOME" & "END" because they're different in different OS
                    case 'H':
                        return HOME;
                    case 'F':
                        return END;
                    default:
                        return thirdKey;
                }
            }
        } catch (IOException e) {
            // print error with red color
            System.err.println("\033[31mRead Key Error!\033[m");
            return -1;
        }
    }

    /**
     * Perform corresponding operation based on the input key
     *
     * @param key input key
     */
    private static void handlekey(int key) {
        // press 'q' to exit
        if (key == 'q') {
            // disable raw mode after exit
            exitEditor();
        } else if (List.of(ARROW_UP, ARROW_DOWN, ARROW_LEFT, ARROW_RIGHT, HOME, END).contains(key)) {
            moveCursor(key);
        }
        // else {
        //     System.out.print((char) key + " (" + key + ")\r\n");
        // }
    }

    /**
     * move cursor position based on the input key
     *
     * @param key input key
     */
    private static void moveCursor(int key) {
        switch (key) {
            case ARROW_UP:
                GUI.cursorUp();
                break;
            case ARROW_DOWN:
                GUI.cursorDown();
                break;
            case ARROW_LEFT:
                GUI.cursorLeft();
                break;
            case ARROW_RIGHT:
                GUI.cursorRight();
                break;
            case HOME:
                GUI.cursorHome();
                break;
            case END:
                GUI.cursorEnd();
                break;
        }
    }

}

/* ============================== GUI =============================== */

/**
 * GUI Operations
 */
final class GUI {

    /**
     * content that will be displayed in the editor
     */
    private static List<String> content = List.of();

    /**
     * cursor coordinate (terminal coordinate is 1 based, thus, initial value is 1)
     */
    private static int cursorX = 1, cursorY = 1, offsetY = 0;

    /**
     * load file content into a String List
     */
    public static void setContent(List<String> content) {
        GUI.content = content;
    }

    /**
     * Refresh screen and print something (just like Vim)
     */
    public static void refreshScreen() {
        StringBuilder builder = new StringBuilder();

        // clearScreen(builder);
        moveCursorToTopLeft(builder);
        drawContent(builder);
        drawStatusBar(builder);
        moveCursorToTopLeft(builder);

        System.out.print(builder);
    }

    /**
     * This method is calculating the offset value
     * And the scolling effect will be rendered later
     */
    public static void scroll() {
        // scroll down when cursor reaches the bottom line and still wants to go down
        if (cursorY >= WindowSize.rowNum + offsetY) {
            offsetY = cursorY - WindowSize.rowNum;
        }
        // scroll up when cursor reaches the top line and still wants to go up
        // if (cursorY - offsetY == 0) {
        //     offsetY--;
        // }
        if (cursorY <= offsetY) {
            offsetY = cursorY - 1;
        }
    }

    private static void drawStatusBar(StringBuilder builder) {
        String editorMessage = "Domenic Zhang's Editor - Osaas";
        String info = "Rows:" + WindowSize.rowNum + " X:" + cursorX + " Y:" + cursorY + " Offset:" + offsetY;

        builder.append("\033[7m");

        int totalLength = info.length() + editorMessage.length();
        // compatible with different window width
        if (WindowSize.colNum >= totalLength + 3) {
            // fill in spaces
            builder.append(info)
                    .append(" ".repeat(Math.max(3, WindowSize.colNum - totalLength)))
                    .append(editorMessage);
        } else {
            // extract part of the string to fit the window width
            builder.append(info.concat("   ").concat(editorMessage), 0, WindowSize.colNum - 3)
                    .append("...");
        }
        builder.append("\033[0m");
    }

    private static void drawContent(StringBuilder builder) {
        int fileFirstRowToDisplay;
        for (int i = 0; i < WindowSize.rowNum; ++i) {
            // offset is the scroll value
            fileFirstRowToDisplay = offsetY + i;
            // only print tilde sign when we don't have enough content in the file
            // (when the file is shorter than window rows)
            if (fileFirstRowToDisplay >= content.size()) {
                builder.append("~");
            } else {
                builder.append(content.get(fileFirstRowToDisplay));
            }
            // "\033[k" means clear from cursor to the end of the line
            builder.append("\033[K\r\n");
        }
    }

    private static void clearScreen(StringBuilder builder) {
        // clear screen
        builder.append("\033[2J");
    }

    private static void moveCursorToTopLeft(StringBuilder builder) {
        // place cursor to the top left corner
        builder.append("\033[H");
    }

    public static void cursorRight() {
        if (cursorX < WindowSize.colNum) {
            cursorX++;
        }
    }

    public static void cursorLeft() {
        if (cursorX > 1) {
            cursorX--;
        }
    }

    public static void cursorDown() {
        if (cursorY < content.size()) {
            cursorY++;
        }
    }

    public static void cursorUp() {
        if (cursorY > 1) {
            cursorY--;
        }
    }

    public static void drawCursor() {
        // refresh cursor's position
        System.out.printf("\033[%d;%dH", cursorY - offsetY, cursorX);
    }

    public static void cursorEnd() {
        cursorX = WindowSize.colNum;
    }

    public static void cursorHome() {
        cursorX = 0;
    }

    /**
     * Exit the GUI<br/>
     * clear screen and reposition the cursor to top left corner
     */
    public static void exitEditor() {
        StringBuilder builder = new StringBuilder();
        clearScreen(builder);
        moveCursorToTopLeft(builder);
        System.out.print(builder);
    }
}

/**
 * Terminal Windows Dimensions (Column and Row)
 */
final class WindowSize {

    public static int rowNum;
    public static int colNum;

    public static void setWindowSize(int rowNum, int colNum) {
        // -1 because we have a status bar
        WindowSize.rowNum = rowNum - 1;
        WindowSize.colNum = colNum;
    }
}

/* =============== Different Operating System Support =============== */

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
        int ISIG = 1, ICANON = 2, ECHO = 10, TCSAFLUSH = 2, IXON = 2000, ICRNL = 400,
                IEXTEN = 100000, OPOST = 1, VMIN = 6, VTIME = 5, TIOCGWINSZ = 0x5413;

        // loading the C standard library for POSIX systems
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
        int ISIG = 1, ICANON = 2, ECHO = 10, TCSAFLUSH = 2, IXON = 2000, ICRNL = 400,
                IEXTEN = 100000, OPOST = 1, VMIN = 6, VTIME = 5, TIOCGWINSZ = 0x40087468;

        // loading the C standard library for POSIX systems
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
        public static final int
                ENABLE_VIRTUAL_TERMINAL_PROCESSING = 0x0004,
                ENABLE_PROCESSED_OUTPUT = 0x0001;

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