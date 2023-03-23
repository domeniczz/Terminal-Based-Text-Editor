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
import java.util.function.BiConsumer;
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

    public static void main(String[] args) {
        openFile(args);
        initEditor();

        while (true) {
            GUI.refreshScreen();
            boolean res = Keys.handlekey();
            if (!res) {
                exitEditor();
            }
        }
    }

    /**
     * Open file specified in arg (only one file)
     *
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
    private static int cursorX = 1, cursorY = 1, offsetX = 0, offsetY = 0;
    private static int pre_cursorX = 1, pre_cursorY = 1, pre_offsetX = 0, pre_offsetY = 0;

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
        scroll();

        StringBuilder builder = new StringBuilder();
        // clearScreen(builder);
        moveCursorToTopLeft(builder);
        drawContent(builder);
        drawStatusBar(builder);
        moveCursorToTopLeft(builder);
        System.out.print(builder);

        drawCursor();
    }

    /**
     * This method is calculating the offset value
     * And the scolling effect will be rendered later
     */
    private static void scroll() {
        /* Vertical Scrolling */
        // scroll *down* when cursor reaches the bottom line and still going down
        if (cursorY >= WindowSize.rowNum + offsetY) {
            offsetY = cursorY - WindowSize.rowNum;
        }
        // scroll *up* when cursor reaches the top line and still going up
        if (cursorY <= offsetY) {
            offsetY = cursorY - 1;
        }

        /* Horizontal Scrolling */
        // scroll *right* when cursor reaches the bottom line and still going right
        if (cursorX >= WindowSize.colNum + offsetX) {
            offsetX = cursorX - WindowSize.colNum;
        }
        // scroll *left* when cursor reaches the top line and still going left
        if (cursorX <= offsetX) {
            offsetX = cursorX - 1;
        }
    }

    private static String searchPrompt = "";

    /**
     * Draw the status bar at the bottom
     */
    private static void drawStatusBar(StringBuilder builder) {
        String statusBarMessage = searchPrompt.isEmpty() ? "Domenic Zhang's Editor - Osaas" : "";
        String info = searchPrompt.isEmpty() ?
                "Rows:" + WindowSize.rowNum + " X:" + cursorX + " Y:" + cursorY /*+ " OffsetY:" + offsetY + " OffsetX:" + offsetX*/ :
                searchPrompt;

        builder.append("\033[7m");

        int totalLength = info.length() + statusBarMessage.length();
        // compatible with different window width
        if (WindowSize.colNum >= totalLength + 3) {
            // fill in spaces
            builder.append(info)
                    .append(" ".repeat(Math.max(3, WindowSize.colNum - totalLength)))
                    .append(statusBarMessage);
        } else {
            // extract part of the string to fit the window width
            builder.append(info.concat("   ").concat(statusBarMessage), 0, WindowSize.colNum - 3)
                    .append("...");
        }
        builder.append("\033[0m");
    }

    /**
     * Draw file content
     */
    private static void drawContent(StringBuilder builder) {
        // fileDisplayRowNum is the line number that will display on the window
        int fileDisplayRowNum;
        for (int i = 0; i < WindowSize.rowNum; ++i) {
            // offsetY is the scrolled value
            fileDisplayRowNum = offsetY + i;
            // only print tilde sign when we don't have enough content in the file
            // (when the file is shorter than window rows)
            if (fileDisplayRowNum >= content.size()) {
                builder.append("~");
            } else {
                String line = content.get(fileDisplayRowNum);
                /*
                 * Judge if current line is longer than window's width
                 * if yes - truncate the line to fit
                 * if no - display normally
                 *
                 * If we don't do that, terminal will automatically wrap the line,
                 * and it will cause flickering when scroll vertically
                 */
                int lineLengthToDisplay = line.length() - offsetX;
                if (lineLengthToDisplay < 0) {
                    lineLengthToDisplay = 0;
                }
                if (lineLengthToDisplay > WindowSize.colNum) {
                    lineLengthToDisplay = WindowSize.colNum;
                }
                if (lineLengthToDisplay > 0) {
                    if (isSearching && lastMatchIndex_Y - offsetY == i) {
                        // in search mode: highlight matched line (same style as status bar)
                        builder.append("\033[7m").append(line, offsetX, offsetX + lineLengthToDisplay).append("\033[0m");
                    } else {
                        builder.append(line, offsetX, offsetX + lineLengthToDisplay);
                    }
                }
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
        String line = currentLine();
        if (line != null && cursorX < line.length() + 1) {
            cursorX++;
        }
        // cursor line wrapping
        else if (line != null && cursorX == line.length() + 1) {
            cursorX = 1;
            cursorY++;
        }
    }

    public static void cursorLeft() {
        if (cursorX > 1) {
            cursorX--;
        }
        // cursor line wrapping
        else if (cursorX == 1 && cursorY > 1) {
            String preLine = previousLine();
            if (preLine != null) {
                cursorX = preLine.length() + 1;
                cursorY--;
            }
        }
    }

    public static void cursorDown() {
        if (cursorY < content.size()) {
            cursorY++;
        }
    }

    public static void cursorDown(int rows) {
        // System.out.println("\033[31mrows:" + rows + "\033[0m ");
        if (cursorY + rows - 1 < content.size()) {
            cursorY = cursorY + rows;
        }
    }

    public static void cursorUp() {
        if (cursorY > 1) {
            cursorY--;
        }
    }

    public static void cursorUp(int rows) {
        System.out.print("\033[31mrows:" + rows + "\033[0m ");
        if (cursorY - rows + 1 > 1) {
            cursorY = cursorY - rows;
        }
    }

    /**
     * Move the cursor to specific position
     *
     * @param x X-axis value (1 based)
     * @param y Y-axis value (1 based)
     */
    public static void moveCursorToCoordinate(int x, int y) {
        // move the cursor to row y, column x
        System.out.printf("\033[%d;%dH", y, x);
    }

    public static void moveCursorToTopOfScreen() {
        cursorY = offsetY + 1;
    }

    public static void moveCursorToBottomOfScreen() {
        cursorY = offsetY + WindowSize.rowNum;
    }

    /**
     * move cursor position based on the input key
     *
     * @param key input key
     */
    public static void moveCursor(int key) {
        switch (key) {
            case Keys.ARROW_UP:
                cursorUp();
                break;
            case Keys.ARROW_DOWN:
                cursorDown();
                break;
            case Keys.ARROW_LEFT:
                cursorLeft();
                break;
            case Keys.ARROW_RIGHT:
                cursorRight();
                break;
            case Keys.PAGE_UP:
                // move cursor to top
                moveCursorToTopOfScreen();
                cursorUp(WindowSize.rowNum);
                break;
            case Keys.PAGE_DOWN:
                // move cursor to bottom
                moveCursorToBottomOfScreen();
                cursorDown(WindowSize.rowNum);
                break;
            case Keys.HOME:
                cursorHome();
                break;
            case Keys.END:
                cursorEnd();
                break;
        }
        GUI.avoidErrorCursorPosition();
    }

    /**
     * Refresh cursor's position
     */
    private static void drawCursor() {
        // refresh cursor's position
        moveCursorToCoordinate(cursorX - offsetX, cursorY - offsetY);
    }

    /**
     * Move cursor to the end of the current line
     */
    public static void cursorEnd() {
        String line = currentLine();
        int len = line != null ? line.length() : 0;
        cursorX = len > 0 ? len + 1 : 1;
    }

    /**
     * Get the line which the cursor are now on
     *
     * @return if Y-axis value of the cursor >= the total lines of the content, it'll return *null*
     */
    public static String currentLine() {
        return cursorY < content.size() ? content.get(cursorY - 1) : null;
    }

    public static String previousLine() {
        if (cursorY > 1) {
            return cursorY < content.size() ? content.get(cursorY - 2) : null;
        } else {
            return currentLine();
        }
    }

    /**
     * Move cursor to the beginning of the current line
     */
    public static void cursorHome() {
        cursorX = 1;
    }

    /**
     * If the cursor is at an unexpected position, reposition it to a right place
     */
    public static void avoidErrorCursorPosition() {
        // if cursor position is beyond the end of the line
        // reposition it to the end of the line
        String line = currentLine();
        if (line != null && cursorX > line.length() + 1) {
            cursorX = line.length() + 1;
        }
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

    /**
     * Search direction
     */
    public enum SearchDirection {
        FORWARD, BACKWARD
    }

    private static SearchDirection searchDirection = SearchDirection.FORWARD;
    private static boolean isSearching = false;
    // line number (Y-axis value) of last matched word
    private static int lastMatchIndex_Y = -1;
    // index number (X-axis value) of last matched word
    private static int lastMatchIndex_X = 0;

    /**
     * Editor Search mode
     */
    public static void editorSearch() {
        storePosition();
        isSearching = true;
        prompt((query, keyPress) -> {
            if (query == null || query.isEmpty()) {
                searchDirection = SearchDirection.FORWARD;
                lastMatchIndex_Y = -1;
                return;
            }

            // natigate forward and backward through all the matches
            if (keyPress == Keys.ARROW_RIGHT || keyPress == Keys.ARROW_DOWN) {
                searchDirection = SearchDirection.FORWARD;
            } else if (keyPress == Keys.ARROW_LEFT || keyPress == Keys.ARROW_UP) {
                searchDirection = SearchDirection.BACKWARD;
            } else {
                searchDirection = SearchDirection.FORWARD;
                lastMatchIndex_Y = -1;
            }

            int contentSize = content.size();
            int currentMatch = lastMatchIndex_Y != -1 ? lastMatchIndex_Y : 0;
            // for loop to search match line by line
            for (int i = 0; i < contentSize; i++) {
                String currentLine = content.get(currentMatch);
                int matchIndex = currentLine.indexOf(query, lastMatchIndex_X);
                lastMatchIndex_X = matchIndex;

                // if find a matchIndex
                if (matchIndex != -1) {
                    lastMatchIndex_Y = currentMatch;
                    cursorY = currentMatch + 1;
                    cursorX = matchIndex + 1;
                    // the value of offsetY doesn't matter as long as it's larger than cursorY,
                    // because the `scroll()` method will set offset to the right value
                    offsetY = contentSize;
                    lastMatchIndex_X++;
                    break;
                } else {
                    lastMatchIndex_X = 0;
                    currentMatch += searchDirection == SearchDirection.FORWARD ? 1 : -1;

                    // if arrive the last line (direction FORWARD), continue searching from the beginning
                    if (currentMatch == contentSize) currentMatch = 0;
                        // if arrive the first line (direction BACKWARD), continue searching from the tail
                    else if (currentMatch == -1) currentMatch = contentSize - 1;
                }
            }
        });
        // reset all parameter value to default;
        searchDirection = SearchDirection.FORWARD;
        isSearching = false;
        lastMatchIndex_Y = -1;
        lastMatchIndex_X = 0;
        restorePosition();
    }

    private static void prompt(BiConsumer<String, Integer> consumer) {
        // the prompt will display on the status bar
        String searchPrompt = "Search %s (Use Esc/Arrows/Enter)";
        StringBuilder userInput = new StringBuilder();

        while (true) {
            GUI.setSearchPrompt(!userInput.toString().isEmpty() ? userInput.toString() : searchPrompt);
            GUI.refreshScreen();
            int key = Keys.readkey();
            // if 'ESC' or 'ENTER', quit search mode
            if (key == '\033' || key == '\r') {
                GUI.setSearchPrompt("");
                return;
            }
            // 'DEL', 'BACKSPACE', 'Ctrl + H' will delete one character
            else if (key == Keys.DEL || key == 51 || key == Keys.BACKSPACE || key == Keys.ctrl('h')) {
                if (userInput.length() > 0) {
                    userInput.deleteCharAt(userInput.length() - 1);
                }
            } else if (!Character.isISOControl(key) && key < 128) {
                userInput.append((char) key);
            }

            consumer.accept(userInput.toString(), key);
        }
    }

    public static void setSearchPrompt(String searchPrompt) {
        GUI.searchPrompt = searchPrompt;
    }

    /**
     * Store current read position before entering search mode
     */
    private static void storePosition() {
        pre_cursorX = cursorX;
        pre_cursorY = cursorY;
        pre_offsetX = offsetX;
        pre_offsetY = offsetY;
    }

    /**
     * Restore current read position after existing search mode
     */
    private static void restorePosition() {
        cursorX = pre_cursorX;
        cursorY = pre_cursorY;
        offsetX = pre_offsetX;
        offsetY = pre_offsetY;
    }

}

final class Keys {

    /**
     * Custom key mapping
     */
    public static final int
            ARROW_UP = 1000,
            ARROW_DOWN = 1001,
            ARROW_LEFT = 1002,
            ARROW_RIGHT = 1003,
            HOME = 1004,
            END = 1005,
            PAGE_UP = 1006,
            PAGE_DOWN = 1007,
            DEL = 1008,
            BACKSPACE = 127;

    /**
     * Read input key
     *
     * @return key mapped number
     */
    public static int readkey() {
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
     */
    public static boolean handlekey() {
        int key = readkey();
        // press 'Ctrl + Q' to exit
        if (key == Keys.ctrl('q')) {
            return false;
        }
        // press 'Ctrl + F' to search
        else if (key == Keys.ctrl('f')) {
            GUI.editorSearch();
        }
        // navigating action
        else if (List.of(ARROW_UP, ARROW_DOWN, ARROW_LEFT, ARROW_RIGHT, HOME, END, PAGE_UP, PAGE_DOWN).contains(key)) {
            GUI.moveCursor(key);
        }
        return true;
    }

    /**
     * Detect Ctrl + key combination
     *
     * @param key combined key
     */
    public static int ctrl(char key) {
        return key & 0x1f;
    }

    /**
     * Detect Shift + key combination
     *
     * @param key combined key
     */
    public static int shift(char key) {
        return key & 0x5f;
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