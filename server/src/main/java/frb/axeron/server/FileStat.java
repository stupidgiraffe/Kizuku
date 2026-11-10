package frb.axeron.server;

import android.os.Parcel;
import android.os.Parcelable;

/** Wire equivalent of Axeron-API 3.0.0's FileStat. */
public record FileStat(String path, String name, String parent, boolean exists, boolean isFile,
                       boolean isDirectory, boolean isHidden, long length, long lastModified,
                       String canonicalPath) implements Parcelable {
    public static final Creator<FileStat> CREATOR = new Creator<>() {
        @Override public FileStat createFromParcel(Parcel source) {
            return new FileStat(source.readString(), source.readString(), source.readString(),
                source.readByte() != 0, source.readByte() != 0, source.readByte() != 0,
                source.readByte() != 0, source.readLong(), source.readLong(), source.readString());
        }

        @Override public FileStat[] newArray(int size) {
            return new FileStat[size];
        }
    };

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(path);
        dest.writeString(name);
        dest.writeString(parent);
        dest.writeByte((byte) (exists ? 1 : 0));
        dest.writeByte((byte) (isFile ? 1 : 0));
        dest.writeByte((byte) (isDirectory ? 1 : 0));
        dest.writeByte((byte) (isHidden ? 1 : 0));
        dest.writeLong(length);
        dest.writeLong(lastModified);
        dest.writeString(canonicalPath);
    }

    @Override public int describeContents() {
        return 0;
    }
}
