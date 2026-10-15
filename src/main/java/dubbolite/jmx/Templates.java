package dubbolite.jmx;

/** 新建元素时使用的 JMeter 5.x 标准 XML 片段（与 JMeter + yajad 插件保存的格式一致） */
final class Templates {

    private Templates() {
    }

    static String element(String kind) {
        switch (kind) {
            case "dubbo":
                return "<com.yajad.jmeter.sampler.DubboSampler guiclass=\"com.yajad.jmeter.sampler.DubboSamplerGui\" "
                        + "testclass=\"com.yajad.jmeter.sampler.DubboSampler\" testname=\"新的 Dubbo 请求\" enabled=\"true\">\n"
                        + yajadProps(false)
                        + "  <stringProp name=\"Yajad.serviceInterface\"></stringProp>\n"
                        + "  <stringProp name=\"Yajad.serviceMethod\"></stringProp>\n"
                        + "  <stringProp name=\"Yajad.serviceParameter\">[\n]</stringProp>\n"
                        + "</com.yajad.jmeter.sampler.DubboSampler>";
            case "zms":
                return "<com.zto.zmsjmeter.ZMSSampler guiclass=\"com.zto.zmsjmeter.ZMSSamplerGui\" "
                        + "testclass=\"com.zto.zmsjmeter.ZMSSampler\" testname=\"新的 ZMS 消息\" enabled=\"true\">\n"
                        + "  <stringProp name=\"zmsZKAddr\"></stringProp>\n"
                        + "  <stringProp name=\"topic\"></stringProp>\n"
                        + "  <stringProp name=\"messageKey\"></stringProp>\n"
                        + "  <stringProp name=\"messageTag\"></stringProp>\n"
                        + "  <stringProp name=\"messageBody\"></stringProp>\n"
                        + "  <stringProp name=\"messageSendMod\">syncSend</stringProp>\n"
                        + "</com.zto.zmsjmeter.ZMSSampler>";
            case "defaults":
                return "<ConfigTestElement guiclass=\"com.yajad.jmeter.config.DubboDefaultsGui\" testclass=\"ConfigTestElement\" "
                        + "testname=\"Yajad Dubbo Defaults\" enabled=\"true\">\n"
                        + yajadProps(true)
                        + "</ConfigTestElement>";
            case "group":
                return "<ThreadGroup guiclass=\"ThreadGroupGui\" testclass=\"ThreadGroup\" testname=\"新分组\" enabled=\"true\">\n"
                        + "  <intProp name=\"ThreadGroup.num_threads\">1</intProp>\n"
                        + "  <intProp name=\"ThreadGroup.ramp_time\">1</intProp>\n"
                        + "  <boolProp name=\"ThreadGroup.same_user_on_next_iteration\">true</boolProp>\n"
                        + "  <stringProp name=\"ThreadGroup.on_sample_error\">continue</stringProp>\n"
                        + "  <elementProp name=\"ThreadGroup.main_controller\" elementType=\"LoopController\" "
                        + "guiclass=\"LoopControlPanel\" testclass=\"LoopController\" testname=\"循环控制器\">\n"
                        + "    <stringProp name=\"LoopController.loops\">1</stringProp>\n"
                        + "    <boolProp name=\"LoopController.continue_forever\">false</boolProp>\n"
                        + "  </elementProp>\n"
                        + "</ThreadGroup>";
            case "pre":
                return script("BeanShellPreProcessor", "BeanShell PreProcessor");
            case "post":
                return script("BeanShellPostProcessor", "BeanShell PostProcessor");
            case "csv":
                return "<CSVDataSet guiclass=\"TestBeanGUI\" testclass=\"CSVDataSet\" testname=\"CSV 数据文件设置\" enabled=\"true\">\n"
                        + "  <stringProp name=\"filename\"></stringProp>\n"
                        + "  <stringProp name=\"fileEncoding\"></stringProp>\n"
                        + "  <stringProp name=\"variableNames\"></stringProp>\n"
                        + "  <boolProp name=\"ignoreFirstLine\">false</boolProp>\n"
                        + "  <stringProp name=\"delimiter\">,</stringProp>\n"
                        + "  <boolProp name=\"quotedData\">false</boolProp>\n"
                        + "  <boolProp name=\"recycle\">true</boolProp>\n"
                        + "  <boolProp name=\"stopThread\">false</boolProp>\n"
                        + "  <stringProp name=\"shareMode\">shareMode.all</stringProp>\n"
                        + "</CSVDataSet>";
            case "vars":
                return "<Arguments guiclass=\"ArgumentsPanel\" testclass=\"Arguments\" testname=\"用户定义的变量\" enabled=\"true\">\n"
                        + "  <collectionProp name=\"Arguments.arguments\"/>\n"
                        + "</Arguments>";
            default:
                throw new IllegalArgumentException("不支持新建的元素类型: " + kind);
        }
    }

    private static String yajadProps(boolean defaults) {
        String[][] kv = {
                {"Yajad.registryProtocol", defaults ? "zookeeper" : ""},
                {"Yajad.registryAddress", ""},
                {"Yajad.registryGroup", ""},
                {"Yajad.rpcProtocol", defaults ? "dubbo" : ""},
                {"Yajad.serviceTimeout", defaults ? "120000" : ""},
                {"Yajad.serviceRetries", defaults ? "0" : ""},
                {"Yajad.serviceVersion", ""},
                {"Yajad.serviceCluster", defaults ? "failfast" : ""},
                {"Yajad.serviceGroup", ""},
                {"Yajad.serviceConnections", defaults ? "100" : ""},
                {"Yajad.serviceLoadBalance", defaults ? "random" : ""},
        };
        StringBuilder sb = new StringBuilder();
        for (String[] p : kv) {
            sb.append("  <stringProp name=\"").append(p[0]).append("\">").append(p[1]).append("</stringProp>\n");
        }
        return sb.toString();
    }

    private static String script(String testclass, String name) {
        return "<" + testclass + " guiclass=\"TestBeanGUI\" testclass=\"" + testclass + "\" testname=\"" + name + "\" enabled=\"true\">\n"
                + "  <stringProp name=\"filename\"></stringProp>\n"
                + "  <stringProp name=\"parameters\"></stringProp>\n"
                + "  <boolProp name=\"resetInterpreter\">false</boolProp>\n"
                + "  <stringProp name=\"script\"></stringProp>\n"
                + "</" + testclass + ">";
    }

    static final String NEW_PLAN = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<jmeterTestPlan version=\"1.2\" properties=\"5.0\" jmeter=\"5.6.3\">\n"
            + "  <hashTree>\n"
            + "    <TestPlan guiclass=\"TestPlanGui\" testclass=\"TestPlan\" testname=\"Dubbo 测试计划\" enabled=\"true\">\n"
            + "      <stringProp name=\"TestPlan.comments\"></stringProp>\n"
            + "      <boolProp name=\"TestPlan.functional_mode\">false</boolProp>\n"
            + "      <boolProp name=\"TestPlan.tearDown_on_shutdown\">true</boolProp>\n"
            + "      <boolProp name=\"TestPlan.serialize_threadgroups\">false</boolProp>\n"
            + "      <elementProp name=\"TestPlan.user_defined_variables\" elementType=\"Arguments\" guiclass=\"ArgumentsPanel\" "
            + "testclass=\"Arguments\" testname=\"用户定义的变量\" enabled=\"true\">\n"
            + "        <collectionProp name=\"Arguments.arguments\"/>\n"
            + "      </elementProp>\n"
            + "      <stringProp name=\"TestPlan.user_define_classpath\"></stringProp>\n"
            + "    </TestPlan>\n"
            + "    <hashTree/>\n"
            + "  </hashTree>\n"
            + "</jmeterTestPlan>\n";

    static JmxDoc newPlan() {
        JmxDoc d = new JmxDoc(NEW_PLAN);
        JmxDoc.TNode g = d.add(d.root.id, -1, "group");
        g.el.setAttr("testname", "Dubbo 请求");
        d.add(g.id, -1, "defaults");
        d.add(g.id, -1, "dubbo");
        d.dirty = false;
        return d;
    }
}
