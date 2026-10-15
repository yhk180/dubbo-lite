package dubbolite;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

final class Json {
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    private Json() {
    }
}
