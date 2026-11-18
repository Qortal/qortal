package org.qortal.rngit;

/**
 * Wire constants of the rngit request protocol ({@code RNS/Utilities/rngit/server.py}).
 * <p>
 * Every response is a byte string whose first byte is a result code, followed by
 * a UTF-8 message or msgpack payload; a fetch that returns a bundle is a file
 * response carrying {@code {IDX_RESULT_CODE: RES_OK}} as metadata instead.
 * Request data is a msgpack map keyed partly by the integer indices below.
 */
public final class RngitProtocol {

    public static final String APP_NAME = "git";
    public static final String ASPECT = "repositories";

    public static final String PATH_LIST = "/git/list";
    public static final String PATH_FETCH = "/git/fetch";
    public static final String PATH_PUSH = "/git/push";
    public static final String PATH_DELETE = "/git/delete";
    public static final String PATH_CREATE = "/git/create";
    public static final String PATH_FORK = "/git/fork";
    public static final String PATH_SYNC = "/git/sync";
    public static final String PATH_MIRROR = "/git/mirror";
    public static final String PATH_RELEASE = "/mgmt/release";
    public static final String PATH_WORK = "/mgmt/work";
    public static final String PATH_PERMS = "/mgmt/perms";

    public static final byte RES_OK = 0x00;
    public static final byte RES_DISALLOWED = 0x01;
    public static final byte RES_INVALID_REQ = 0x02;
    public static final byte RES_NOT_FOUND = 0x03;
    public static final byte RES_REMOTE_FAIL = (byte) 0xFF;

    public static final int IDX_REPOSITORY = 0x00;
    public static final int IDX_RESULT_CODE = 0x01;
    public static final int IDX_GROUP = 0x02;

    /** Longest group or repository name accepted in a request path. */
    public static final int NAME_LIMIT = 256;

    /** Identity hashes are 16 bytes, written as 32 hex characters. */
    public static final int IDENTITY_HASH_HEX_LENGTH = 32;

    /** Permissions a creator receives on a repository they created. */
    public static final String REPO_CREATE_PERMS_TEMPLATE = "adm:{IDENTITY_HASH}";

    private RngitProtocol() {
    }
}
