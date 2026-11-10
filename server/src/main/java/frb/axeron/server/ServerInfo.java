package frb.axeron.server;

import android.os.Parcel;
import android.os.Parcelable;

/** Wire equivalent of Axeron-API 3.0.0's parcelized ServerInfo. */
public record ServerInfo(String version, long versionCode, int uid, int pid,
                         String selinuxContext, long starting, boolean permission,
                         String managerCert, boolean callerIsManager) implements Parcelable {
    public static final Creator<ServerInfo> CREATOR = new Creator<>() {
        @Override public ServerInfo createFromParcel(Parcel source) {
            return new ServerInfo(source.readString(), source.readLong(), source.readInt(),
                source.readInt(), source.readString(), source.readLong(), source.readInt() != 0,
                source.readString(), source.readInt() != 0);
        }

        @Override public ServerInfo[] newArray(int size) {
            return new ServerInfo[size];
        }
    };

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(version);
        dest.writeLong(versionCode);
        dest.writeInt(uid);
        dest.writeInt(pid);
        dest.writeString(selinuxContext);
        dest.writeLong(starting);
        dest.writeInt(permission ? 1 : 0);
        dest.writeString(managerCert);
        dest.writeInt(callerIsManager ? 1 : 0);
    }

    @Override public int describeContents() {
        return 0;
    }
}
