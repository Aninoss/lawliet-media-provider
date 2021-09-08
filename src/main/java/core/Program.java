package core;

public class Program {

    public static void init() {
        System.out.println("-------------------------------------");
        System.out.println("Production Mode: " + isProductionMode());
        System.out.println("-------------------------------------");
    }

    public static boolean isProductionMode() {
        return System.getenv("PRODUCTION").equals("true");
    }

}
