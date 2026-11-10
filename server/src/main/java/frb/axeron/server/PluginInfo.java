package frb.axeron.server;

import android.os.Parcel;
import android.os.Parcelable;

/** Wire equivalent of Axeron-API 3.0.0's JSON-backed PluginInfo. */
public record PluginInfo(String json) implements Parcelable {
    public static final Creator<PluginInfo> CREATOR = new Creator<>() {
        @Override public PluginInfo createFromParcel(Parcel source) {
            return new PluginInfo(source.readString());
        }

        @Override public PluginInfo[] newArray(int size) {
            return new PluginInfo[size];
        }
    };

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(json);
    }

    @Override public int describeContents() {
        return 0;
    }
}
