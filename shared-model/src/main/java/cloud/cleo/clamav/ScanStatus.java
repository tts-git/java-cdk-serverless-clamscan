package cloud.cleo.clamav;

/**
 * Possible Statuses that can be applied for a scan for tagging.
 */
public enum ScanStatus {

    /**
     * Scan was completed and found to be clean.
     */
    CLEAN,
    /**
     * Scan was completed and found to be infected.
     */
    INFECTED,
    /**
     * Scan aborted because file size is too big to scan.
     */
    FILE_SIZE_EXCEEED,
    /**
     * Scanning is in progress.
     */
    SCANNING,
    /**
     * An error occurred during the scanning progress.
     */
    ERROR;

    /**
     * Name of tag to be used for the scanning status.
     */
    public static final String SCAN_TAG_NAME = "scan-status";

    /**
     * When true, only set tagging on object when it is infected. This makes it easier to fire a lambda on Tag event to
     * react to infected files. Otherwise when false tagging events will fire on all statuses which may not be what you
     * want. If you want to deny access via bucket policy while scanning, then this will need to be false.
     */
    public final static Boolean ONLY_TAG_INFECTED;

    static {
        boolean onlyTagInfected = true; // Default to true
        String envValue = System.getenv("ONLY_TAG_INFECTED");

        if (envValue != null) {
            envValue = envValue.trim().toLowerCase();
            switch (envValue) {
                case "true" ->
                    onlyTagInfected = true;
                case "false" ->
                    onlyTagInfected = false;
                default -> // Invalid value, log it if you want (optional)
                    System.err.println("WARNING: Invalid value for ONLY_TAG_INFECTED: " + envValue + " (defaulting to true)");
            }
        }

        ONLY_TAG_INFECTED = onlyTagInfected;
    }

    // Max size in bytes to process (100MB is safe given 512MB /tmp in Lambda)
    public final static int MAX_BYTES = 104857600;

    // Function Name for the Lambda
    public static final String LAMBDA_NAME = "ClamavLambdaFunction";

    // Function Name for the Lambda
    public static final String LAMBDA_ALIAS_NAME = "live";
    
    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: ScanStatus <CONSTANT_NAME>");
            System.exit(1);
        }

        switch (args[0]) {
            case "LAMBDA_NAME" ->
                System.out.println(LAMBDA_NAME);
            case "LAMBDA_ALIAS_NAME" ->
                System.out.println(LAMBDA_ALIAS_NAME);
            case "SCAN_TAG_NAME" ->
                System.out.println(SCAN_TAG_NAME);
            default -> {
                System.err.println("Unknown constant: " + args[0]);
                System.exit(2);
            }
        }
    }
}
