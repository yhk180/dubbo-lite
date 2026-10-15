package dubbolite.run;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;

/** 提供给 BeanShell / JSR223 脚本的 JMeter 兼容对象：prev、sampler、ctx */
public final class ScriptApi {

    private ScriptApi() {
    }

    /** 对应 JMeter 的 SampleResult（只保留脚本常用的方法） */
    public static class Result {
        private String label = "";
        private String responseData = "";
        private String samplerData = "";
        private boolean successful = true;
        private long time;
        private String responseCode = "";
        private String responseMessage = "";

        public String getResponseDataAsString() { return responseData; }
        public byte[] getResponseData() { return responseData.getBytes(StandardCharsets.UTF_8); }
        public void setResponseData(String data) { this.responseData = data == null ? "" : data; }
        public void setResponseData(String data, String encoding) { setResponseData(data); }
        public boolean isSuccessful() { return successful; }
        public void setSuccessful(boolean successful) { this.successful = successful; }
        public long getTime() { return time; }
        public void setTime(long time) { this.time = time; }
        public String getSampleLabel() { return label; }
        public void setSampleLabel(String label) { this.label = label; }
        public String getSamplerData() { return samplerData; }
        public void setSamplerData(String s) { this.samplerData = s; }
        public String getResponseCode() { return responseCode; }
        public void setResponseCode(String c) { this.responseCode = c; }
        public String getResponseMessage() { return responseMessage; }
        public void setResponseMessage(String m) { this.responseMessage = m; }
        public String getDataType() { return "text"; }
        public String getContentType() { return "text/plain"; }
        public String getRequestHeaders() { return ""; }
        public String getResponseHeaders() { return ""; }
        public String getThreadName() { return "Dubbo Lite 1-1"; }
    }

    /** 对应 JMeter 的 sampler 对象，读写的是本次请求的字段（JMeter 原始属性名） */
    public static class Sampler {
        private final String name;
        private final Map<String, String> props;

        Sampler(String name, Map<String, String> props) {
            this.name = name;
            this.props = props;
        }

        public String getName() { return name; }
        public String getPropertyAsString(String key) {
            String v = props.get(key);
            return v == null ? "" : v;
        }
        public String getPropertyAsString(String key, String def) {
            String v = props.get(key);
            return v == null ? def : v;
        }
        public void setProperty(String key, String value) { props.put(key, value); }
        public void setProperty(String key, int value) { props.put(key, String.valueOf(value)); }
        public String getThreadName() { return "Dubbo Lite 1-1"; }
    }

    /** 对应 JMeterContext */
    public static class Ctx {
        private final Vars vars;
        private final Properties props;
        private final Result prev;
        private final Sampler sampler;

        Ctx(Vars vars, Properties props, Result prev, Sampler sampler) {
            this.vars = vars;
            this.props = props;
            this.prev = prev;
            this.sampler = sampler;
        }

        public Vars getVariables() { return vars; }
        public Properties getProperties() { return props; }
        public Result getPreviousResult() { return prev; }
        public Sampler getCurrentSampler() { return sampler; }
        public Sampler getPreviousSampler() { return sampler; }
        public int getThreadNum() { return 0; }
        public boolean isSamplingStarted() { return true; }
    }
}
