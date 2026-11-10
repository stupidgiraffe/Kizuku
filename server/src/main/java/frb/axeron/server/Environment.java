package frb.axeron.server;

import android.os.BadParcelableException;
import android.os.Parcel;
import android.os.Parcelable;
import java.util.LinkedHashMap;
import java.util.Map;

/** Wire equivalent of Axeron-API 3.0.0's Environment. */
public record Environment(Map<String, String> values) implements Parcelable {
    public Environment {
        values = Map.copyOf(values);
    }

    public static final Creator<Environment> CREATOR = new Creator<>() {
        @Override public Environment createFromParcel(Parcel source) {
            int count = source.readInt();
            if (count < 0 || count > source.dataAvail() / 8) {
                throw new BadParcelableException("Invalid environment entry count");
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                String name = source.readString();
                String value = source.readString();
                if (name == null || value == null) {
                    throw new BadParcelableException("Missing environment entry");
                }
                values.put(name, value);
            }
            return new Environment(values);
        }

        @Override public Environment[] newArray(int size) {
            return new Environment[size];
        }
    };

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(values.size());
        values.forEach((name, value) -> {
            dest.writeString(name);
            dest.writeString(value);
        });
    }

    @Override public int describeContents() {
        return 0;
    }
}
