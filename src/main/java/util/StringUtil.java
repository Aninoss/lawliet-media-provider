package util;

public class StringUtil {

    public static boolean stringIsAlphanumeric(String string) {
        for (char c : string.toCharArray()) {
            if (!Character.isLetterOrDigit(c)) {
                return false;
            }
        }
        return true;
    }

}
