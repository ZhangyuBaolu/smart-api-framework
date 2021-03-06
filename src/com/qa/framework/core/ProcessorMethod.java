package com.qa.framework.core;

import com.library.common.DynamicCompileHelper;
import com.library.common.JsonHelper;
import com.library.common.ReflectHelper;
import com.library.common.StringHelper;
import com.qa.framework.bean.*;
import com.qa.framework.cache.StringCache;
import com.qa.framework.library.database.DBHelper;
import com.qa.framework.library.httpclient.HttpMethod;
import com.qa.framework.util.StringUtil;
import com.qa.framework.verify.ContainExpectResult;
import com.qa.framework.verify.IExpectResult;
import com.qa.framework.verify.PairExpectResult;
import org.apache.log4j.Logger;
import org.testng.Assert;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Created by Administrator on 2016/12/6.
 */
public class ProcessorMethod {
    protected static Logger logger = Logger.getLogger(ProcessorMethod.class);

    public static String request(String url, List<Param> params, String httpMethod, boolean storeCookie, boolean useCookie) {
        String content = null;
        if (params != null) {
            for (Param param : params) {
                logger.info("--------" + param.toString());
            }
        }
        switch (httpMethod) {
            case "get":
                content = HttpMethod.useGetMethod(url, params, storeCookie, useCookie);
                break;
            case "post":
                content = HttpMethod.usePostMethod(url, params, storeCookie, useCookie,false);
                break;
            case "put":
                content = HttpMethod.usePutMethod(url, params, storeCookie, useCookie);
                break;
            case "delete":
                content = HttpMethod.usePutMethod(url, params, storeCookie, useCookie);
                break;
        }

        content = JsonHelper.decodeUnicode(content);
        logger.info("返回的信息:" + JsonHelper.decodeUnicode(content));
        Assert.assertNotNull(content, "response返回空");
        return content;
    }

    public static void processBefore(TestData testData) {
        if (testData.getBefore() != null)
            try {
                logger.info("Process Before in xml-" + testData.getCurrentFileName() + " TestData-" + testData.getName());
                Before before = testData.getBefore();
                if (before.getSqls() != null) {
                    List<Sql> sqls = before.getSqls();
                    for (Sql sql : sqls) {
                        logger.info("需更新语句：" + sql.getSqlStatement());
                        DBHelper.executeUpdate(sql.getSqlStatement());
                    }
                } else if (before.getFunctions() != null) {
                    List<Function> functions = before.getFunctions();
                    for (Function function : functions) {
                        Class cls = Class.forName(function.getClsName());
                        Method method = cls.getDeclaredMethod(function.getMethodName());
                        Object object = cls.newInstance();
                        method.invoke(object);
                    }
                }
            } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
                e.printStackTrace();
            }
    }


    public static void processSetupParam(TestData testData, StringCache stringCache) {
        List<Setup> setupList = testData.getSetupList();
        if (setupList != null) {
            for (Setup setup : setupList) {
                List<Param> setupParamList = setup.getParams();
                if (setupParamList != null) {
                    for (Param param : setupParamList) {
                        executeFunction(param, setup, testData, stringCache);
                        executeSql(param, setup, testData, stringCache);
                        processParamPair(param, setup, testData, stringCache);
                        processParamDate(param, setup, testData, stringCache);
                        processParamFromSetup(testData, param, stringCache);
                    }
                }
            }
        }
    }

    public static void processTestDataParam(TestData testData, StringCache stringCache) {
        List<Param> paramList = testData.getParams();
        if (paramList != null) {
            for (Param param : paramList) {
                processParamDate(param, null, testData, stringCache);
                executeFunction(param, null, testData, stringCache);
                executeSql(param, null, testData, stringCache);
                processParamPair(param, null, testData, stringCache);
                processParamFromSetup(testData, param, stringCache);
                processParamFromOtherTestData(param, stringCache);
                //processParamPair(param);
            }
        }
        processExpectResult(testData, stringCache);

    }

    public static void executeFunction(Param param, Setup setup, TestData testData, StringCache stringCache) {
        if (param.getFunction() != null) {
            try {
                Class cls = Class.forName(param.getFunction().getClsName());
                Method method = cls.getDeclaredMethod(param.getFunction().getMethodName());
                Object object = cls.newInstance();
                Object value = method.invoke(object);
                param.setValue(value.toString());
                stringCache.put(param.getName(), param.getValue());
                stringCache.put(testData.getName() + "." + param.getName(), param.getValue());
                if (setup != null) {
                    stringCache.put(setup.getName() + "." + param.getName(), param.getValue());
                    stringCache.put(testData.getName() + "." + setup.getName() + "." + param.getName(), param.getValue());
                }
            } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Process test data param.
     *
     * @param param       the param
     * @param setup       the setup
     * @param testData    the test data
     * @param stringCache
     */
    public static void executeSql(Param param, Setup setup, TestData testData, StringCache stringCache) {
        if (param.getSqls() != null) {
            List<Sql> sqlList = param.getSqls();
            executeSql(sqlList, param, setup, testData, stringCache);
            if (param.getValue().contains("#{")) {
                //处理语句中的#{}问题
                //第一步将#{\\S+}的值找出来
                List<String> lists = StringHelper.find(param.getValue(), "#\\{[a-zA-Z0-9._]*\\}");
                String[] replacedStr = new String[lists.size()];   //替换sql语句中的#{}
                int i = 0;
                for (String list : lists) {
                    //去掉#{}
                    String proStr = list.substring(2, list.length() - 1);
                    //从缓存中去取相应的值
                    replacedStr[i++] = stringCache.getValue(proStr);
                }
                param.setValue(StringUtil.handleSpecialChar(param.getValue(), replacedStr));
            }

            stringCache.put(param.getName(), param.getValue());
            stringCache.put(testData.getName() + "." + param.getName(), param.getValue());
            if (setup != null) {
                stringCache.put(setup.getName() + "." + param.getName(), param.getValue());
                stringCache.put(testData.getName() + "." + setup.getName() + "." + param.getName(), param.getValue());
            }
        }
    }

    public static void executeSql(List<Sql> sqlList, StringCache stringCache) {
        for (Sql sql : sqlList) {
            if (sql.getSqlStatement().contains("#{")) {
                //处理sql语句中的#{}问题
                //第一步将#{\\S+}的值找出来
                List<String> lists = StringHelper.find(sql.getSqlStatement(), "#\\{[a-zA-Z0-9._]*\\}");
                String[] replacedStr = new String[lists.size()];   //替换sql语句中的#{}
                int i = 0;
                for (String list : lists) {
                    //去掉#{}
                    String proStr = list.substring(2, list.length() - 1);
                    //从缓存中去取相应的值
                    replacedStr[i++] = stringCache.getValue(proStr);
                }
                sql.setSqlStatement(StringUtil.handleSpecialChar(sql.getSqlStatement(), replacedStr));
            }
            logger.debug("最终的SQL为:" + sql.getSqlStatement());
            Map<String, Object> recordInfo = DBHelper.queryOneRow(sql.getSqlStatement());//查询数据库, 将返回值按照returnValues的值放入HashMap
            for (int i = 0; i < sql.getReturnValues().length; i++) {
                String key = sql.getReturnValues()[i];
                String sqlkey = sql.getName() + "." + sql.getReturnValues()[i];
                Assert.assertNotNull(recordInfo, "sql为" + sql.getSqlStatement());
                String value = null;
                if (recordInfo.get(key) == null) {
                    value = "";
                } else {
                    value = recordInfo.get(key).toString();
                }
                stringCache.put(sqlkey, value);                         //将sql.属性的值存入缓存
            }
        }

    }

    /**
     * Execute sql string. 处理sql中#{}, 以及执行相应的sql, 生成最后的结果
     *
     * @param sqlList  the sqlList  sql列表
     * @param param    the param      当前传入的参数
     * @param setup    the setup    param所属的setup
     * @param testData the test data
     * @return the string
     */
    public static String executeSql(List<Sql> sqlList, Param param, Setup setup, TestData testData, StringCache stringCache) {
        for (Sql sql : sqlList) {
            if (sql.getSqlStatement().contains("#{")) {
                //处理sql语句中的#{}问题
                //第一步将#{\\S+}的值找出来
                List<String> lists = StringHelper.find(sql.getSqlStatement(), "#\\{[a-zA-Z0-9._]*\\}");
                String[] replacedStr = new String[lists.size()];   //替换sql语句中的#{}
                int i = 0;
                for (String list : lists) {
                    //去掉#{}
                    String proStr = list.substring(2, list.length() - 1);
                    //从缓存中去取相应的值
                    replacedStr[i++] = stringCache.getValue(proStr);
                }
                sql.setSqlStatement(StringUtil.handleSpecialChar(sql.getSqlStatement(), replacedStr));
            }
            logger.debug("最终的SQL为:" + sql.getSqlStatement());
            Map<String, Object> recordInfo = DBHelper.queryOneRow(sql.getSqlStatement());//查询数据库, 将返回值按照returnValues的值放入HashMap
            for (int i = 0; i < sql.getReturnValues().length; i++) {
                String key = sql.getReturnValues()[i];
                String sqlkey = sql.getName() + "." + sql.getReturnValues()[i];
                String paramSqlKey = param.getName() + "." + sqlkey;
                String testDatakey = testData.getName() + "." + paramSqlKey;
                Assert.assertNotNull(recordInfo, "sql为" + sql.getSqlStatement());
                String value = null;
                if (recordInfo == null || recordInfo.get(key) == null) {
                    value = " ";
                } else {
                    value = recordInfo.get(key).toString();
                }
                stringCache.put(sqlkey, value);                         //将sql.属性的值存入缓存
                stringCache.put(paramSqlKey, value);
                stringCache.put(testDatakey, value);
                if (setup != null) {
                    String setupParamSqlKey = setup.getName() + "." + paramSqlKey;
                    String testDataSetupParamSqlKey = testData.getName() + "." + setupParamSqlKey;
                    stringCache.put(setupParamSqlKey, value);
                    stringCache.put(testDataSetupParamSqlKey, value);
                }
            }
        }
        //将值赋给paramValue
        String decodeValue = param.getValue();
        if (decodeValue == null) {
            throw new RuntimeException("请检查param:" + param + "是否有值");
        }
        decodeValue = decodeValue.substring(2, decodeValue.length() - 1);
        //param的值可能有3种情况setup, sql.id, param.sql.id, 但是实际上只会是sql.id一种, 因为setup的情况不处理, 若是param.sql.id则sqlstatement必为空
        return stringCache.getValue(decodeValue);
    }


    /**
     * Process param pair. 处理Param中的键值对, 转化成url能识别的格式
     *
     * @param param       the param
     * @param setup
     * @param testData
     * @param stringCache
     */
    public static void processParamPair(Param param, Setup setup, TestData testData, StringCache stringCache) {
        if (param.getPairs() != null) {
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("{");
            for (Pair pair : param.getPairs()) {
                processPair(pair, stringCache);
                stringBuilder.append("\"" + pair.getKey() + "\"" + pair.getValue() + ",");
            }
            stringBuilder.deleteCharAt(stringBuilder.length() - 1);
            stringBuilder.append("}");
            param.setValue(stringBuilder.toString());
            stringCache.put(param.getName(), param.getValue());
            stringCache.put(testData.getName() + "." + param.getName(), param.getValue());
            if (setup != null) {
                stringCache.put(setup.getName() + "." + param.getName(), param.getValue());
                stringCache.put(testData.getName() + "." + setup.getName() + "." + param.getName(), param.getValue());
            }
        }
    }

    /**
     * Process pair.
     *
     * @param pair        the pair
     * @param stringCache
     */
    public static void processPair(Pair pair, StringCache stringCache) {
        if (pair.getValue().contains("#{")) {
            String OriginalString = pair.getValue();
            logger.info("original expect pair string:" + OriginalString);
            List<String> lists = StringHelper.find(OriginalString, "#\\{.*?\\}");
            String[] placeHolders = new String[lists.size()];
            String[] placeHoldersValue = new String[lists.size()];
            int count = 0;
            for (String list : lists) {
                if (list.contains("+") || list.contains("-") || list.contains("*") || list.contains("/")) {
                    String calculateStr = list.substring(2, list.length() - 1);
                    List<String> keyList = new ArrayList<String>();
                    for (String key : stringCache.mapCache.keySet()) {
                        keyList.add(key);
                    }
                    Collections.sort(keyList, new Comparator<String>() {
                        public int compare(String key1, String key2) {
                            return key2.length() - key1.length();
                        }
                    });
                    for (String key : keyList) {
                        if (calculateStr.contains(key)) {
                            calculateStr = calculateStr.replace(key, stringCache.getValue(key));
                        }
                    }
                    try {
                        Object cal = DynamicCompileHelper.evalCalculate(calculateStr);
                        OriginalString = OriginalString.replace(list, cal.toString());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } else {
                    String inner = list.substring(2, list.length() - 1);
                    OriginalString = OriginalString.replace(list, inner);
                    if (!pair.isSort()) {
                        for (String key : stringCache.mapCache.keySet()) {
                            if (inner.equals(key)) {
                                OriginalString = OriginalString.replace(key, stringCache.getValue(key));
                            }
                        }
                    } else {
                        String value = stringCache.getValue(inner);
                        placeHolders[count] = inner;
                        placeHoldersValue[count] = value;
                        count++;
                    }
                }
            }
            if (pair.isSort()) {
                Arrays.sort(placeHoldersValue);
                for (int i = 0; i < count; i++) {
                    OriginalString = OriginalString.replace(placeHolders[i], placeHoldersValue[i]);
                }
            }
            logger.info("after replace,expect pair string:" + OriginalString);
            pair.setValue(OriginalString);
        }
    }

    /**
     * 处理param中需要接受setup中param值的问题
     *
     * @param testData    the test data
     * @param param       the param
     * @param stringCache
     */
    public static void processParamFromSetup(TestData testData, Param param, StringCache stringCache) {
        if (testData.getSetupList() != null && param.getSqls() == null && param.getFunction() == null && param.getDateStamp() == null && param.getPairs() == null) {
            if (param.getValue().contains("#{") && param.getValue().contains(".")) {
                //remove #{}
                String setupNameAndParam = param.getValue().substring(2, param.getValue().length() - 1);
                param.setValue(stringCache.getValue(setupNameAndParam));
            }
        }
    }

    public static void processSetupResultParam(TestData testData, StringCache stringCache) {
        if (testData.getSetupList() != null) {
            testData.setUseCookie(true);
            for (Setup setup : testData.getSetupList()) {
                logger.info("Process Setup in xml-" + testData.getCurrentFileName() + " TestData-" + testData.getName() + " Setup-" + setup.getName());
                String content = ProcessorMethod.request(setup.getUrl(), setup.getParams(), setup.getHttpMethod(), setup.isStoreCookie(), setup.isUseCookie());
                Map<String, Object> jsonObject = JsonHelper.getJsonMapString(content);
                if (jsonObject.size() > 0) {
                    Set<String> Set = jsonObject.keySet();
                    for (String key : Set) {
                        Object object = jsonObject.get(key);
                        if (object instanceof Map) {
                            Map<String, Object> map = (Map<String, Object>) object;
                            for (String subKey : map.keySet()) {
                                stringCache.put(subKey, map.get(subKey).toString());
                                stringCache.put(setup.getName() + "." + subKey, map.get(subKey).toString());
                            }
                        } else if (object instanceof List) {
                            List<Map<String, Object>> listMap = (List<Map<String, Object>>) object;
                            for (Map<String, Object> map : listMap) {
                                for (String subKey : map.keySet()) {
                                    stringCache.put(subKey, map.get(subKey).toString());
                                    stringCache.put(setup.getName() + "." + subKey, map.get(subKey).toString());
                                }
                            }
                        } else {
                            stringCache.put(key, object.toString());
                            stringCache.put(setup.getName() + "." + key, object.toString());
                        }
                    }
                }
            }
        }
    }

    private static void processParamDate(Param param, Setup setup, TestData testData, StringCache stringCache) {
        if (param.getDateStamp() != null) {
            DateStamp dateStamp = param.getDateStamp();
            Calendar c = Calendar.getInstance();
            Field[] fields = dateStamp.getClass().getDeclaredFields(); //遍历成员变量, 寻找哪些属性不为空
            if (dateStamp.getBaseTime() == null || "".equalsIgnoreCase(dateStamp.getBaseTime())) {
                c.setTime(new Date());
            } else {
                SimpleDateFormat dateFormater = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
                try {
                    Date date = dateFormater.parse(dateStamp.getBaseTime());
                    c.setTime(date);
                } catch (ParseException e) {
                    e.printStackTrace();
                }
            }
            List<Field> setFields = new ArrayList<Field>();
            List<Field> addFields = new ArrayList<Field>();
            for (Field field : fields) {
                Object obj = ReflectHelper.getMethod(dateStamp, field.getName());
                if (obj != null && !"".equalsIgnoreCase(obj.toString())) {
                    if (field.getName().contains("set")) {
                        setFields.add(field);
                    } else if (field.getName().contains("add")) {
                        addFields.add(field);
                    }
                }
            }

            if (addFields != null) {
                for (Field field : addFields) {
                    Object obj = ReflectHelper.getMethod(dateStamp, field.getName());
                    if (field.getName().contains("Year")) {
                        c.add(Calendar.YEAR, Integer.parseInt(obj.toString()));
                    } else if (field.getName().contains("Month")) {
                        c.add(Calendar.MONTH, Integer.parseInt(obj.toString()));
                    } else if (field.getName().contains("Day")) {
                        c.add(Calendar.DAY_OF_MONTH, Integer.parseInt(obj.toString()));
                    } else if (field.getName().contains("Hour")) {
                        c.add(Calendar.HOUR_OF_DAY, Integer.parseInt(obj.toString()));
                    } else if (field.getName().contains("Min")) {
                        c.add(Calendar.MINUTE, Integer.parseInt(obj.toString()));
                    } else if (field.getName().contains("Second")) {
                        c.add(Calendar.SECOND, Integer.parseInt(obj.toString()));
                    }
                }
            }
            if (setFields != null) {
                for (Field field : setFields) {
                    Object obj = ReflectHelper.getMethod(dateStamp, field.getName());
                    if (obj != null && !"".equalsIgnoreCase(obj.toString())) {
                        if (field.getName().contains("Year")) {
                            c.set(Calendar.YEAR, Integer.parseInt(obj.toString()));
                        } else if (field.getName().contains("Month")) {
                            c.set(Calendar.MONTH, Integer.parseInt(obj.toString()) - 1);
                        } else if (field.getName().contains("Day")) {
                            c.set(Calendar.DAY_OF_MONTH, Integer.parseInt(obj.toString()));
                        } else if (field.getName().contains("Hour")) {
                            c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(obj.toString()));
                        } else if (field.getName().contains("Min")) {
                            c.set(Calendar.MINUTE, Integer.parseInt(obj.toString()));
                        } else if (field.getName().contains("Second")) {
                            c.set(Calendar.SECOND, Integer.parseInt(obj.toString()));
                        }
                    }
                }
            }
            logger.info("设置的时间为:" + c.getTime());
            if (dateStamp.getDateFormat() != null) {
                param.setValue(new SimpleDateFormat(dateStamp.getDateFormat()).format(c.getTime()));
            } else {
                param.setValue(c.getTimeInMillis() / 1000 + "");
            }
            stringCache.put(param.getName(), param.getValue());
            stringCache.put(testData.getName() + "." + param.getName(), param.getValue());
            if (setup != null) {
                stringCache.put(setup.getName() + "." + param.getName(), param.getValue());
                stringCache.put(testData.getName() + "." + setup.getName() + "." + param.getName(), param.getValue());
            }
        }
    }

    public static void processParamFromOtherTestData(Param param, StringCache stringCache) {
        if (param.getSqls() == null && param.getFunction() == null && param.getDateStamp() == null && param.getPairs() == null) {
            if (param.getValue() != null) {
                if (param.getValue().contains("#{") && param.getValue().contains(".")) {
                    //remove #{}
                    String testDataNameAndParam = param.getValue().substring(2, param.getValue().length() - 1);
                    if (testDataNameAndParam.contains("#{")) {
                        StringBuilder newParamValue = new StringBuilder();
                        String[] residues = testDataNameAndParam.split("\\}#\\{");
                        for (int i = 0; i < residues.length; i++) {
                            newParamValue.append(stringCache.getValue(residues[i]));
                        }
                        param.setValue(newParamValue.toString());
                    } else {
                        String value = stringCache.getValue(testDataNameAndParam);
                        if (value == null) {
                            logger.info("无法找到关键字为" + testDataNameAndParam + "的值，请检查");
                        }
                        param.setValue(value);
                    }
                }
            }
        }
    }

    /**
     * Process expect result.
     *
     * @param testData    the test data
     * @param stringCache
     */
    public static void processExpectResult(TestData testData, StringCache stringCache) {
        ExpectResult expectResult = testData.getExpectResult();
        for (IExpectResult result : expectResult.getExpectResultImp()) {
            if (result instanceof ContainExpectResult) {
                ContainExpectResult containExpectResult = (ContainExpectResult) result;
                if (containExpectResult.getSqls() != null) {
                    List<Sql> sqlList = containExpectResult.getSqls();
                    executeSql(sqlList, stringCache);
                }
                if (containExpectResult.getTextStatement().contains("#{")) {
                    //处理语句中的#{}问题
                    //第一步将#{\\S+}的值找出来
                    List<String> lists = StringHelper.find(containExpectResult.getTextStatement(), "#\\{[a-zA-Z0-9._]*\\}");
                    String[] replacedStr = new String[lists.size()];   //替换sql语句中的#{}
                    int i = 0;
                    for (String list : lists) {
                        //去掉#{}
                        String proStr = list.substring(2, list.length() - 1);
                        //从缓存中去取相应的值
                        replacedStr[i++] = stringCache.getValue(proStr);
                    }
                    containExpectResult.setTextStatement(StringUtil.handleSpecialChar(containExpectResult.getTextStatement(), replacedStr));
                }
            } else if (result instanceof PairExpectResult) {
                PairExpectResult pairExpectResult = (PairExpectResult) result;
                for (Pair pair : pairExpectResult.getPairs()) {
                    if (pair.getValue().contains("#{")) {
                        //处理语句中的#{}问题
                        //第一步将#{\\S+}的值找出来
                        List<String> lists = StringHelper.find(pair.getValue(), "#\\{[a-zA-Z0-9._]*\\}");
                        String[] replacedStr = new String[lists.size()];   //替换sql语句中的#{}
                        int i = 0;
                        for (String list : lists) {
                            //去掉#{}
                            String proStr = list.substring(2, list.length() - 1);
                            //从缓存中去取相应的值
                            replacedStr[i++] = stringCache.getValue(proStr);
                        }
                        pair.setValue(StringUtil.handleSpecialChar(pair.getValue(), replacedStr));
                    }
                }
            } else {
                throw new IllegalArgumentException("没有匹配的期望结果集！");
            }
        }
    }


}
