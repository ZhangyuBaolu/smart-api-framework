package com.qa.framework.factory;

import com.qa.framework.bean.TestData;
import com.qa.framework.config.PropConfig;
import com.qa.framework.core.ParamValueProcessor;
import com.qa.framework.core.TestBase;
import com.qa.framework.testnglistener.PowerEmailableReporter;
import com.qa.framework.testnglistener.TestResultListener;
import org.testng.ITestContext;
import org.testng.ITestNGMethod;
import org.testng.annotations.*;

import java.lang.reflect.Method;

/**
 * The type Executor.
 */
@Listeners({TestResultListener.class, PowerEmailableReporter.class})
public class Executor extends TestBase {
    private TestData testData;
    private String url;
    private String httpMethod;

    /**
     * Instantiates a new Executor.
     *
     * @param testData   the test data
     * @param url        the url
     * @param httpMethod the http method
     */
    public Executor(TestData testData, String url, String httpMethod) {
        this.testData = testData;
        this.url = url;
        this.httpMethod = httpMethod;
    }

    /**
     * Gets test data.
     *
     * @return the test data
     */
    public TestData getTestData() {
        return testData;
    }

    /**
     * Data object [ ] [ ].
     *
     * @return the object [ ] [ ]
     */
    @DataProvider
    public Object[][] data() {
        return new Object[][]{
                {testData, url, httpMethod},
        };
    }

    @BeforeMethod(alwaysRun = true)
    public void BeforeMethod(Method method, Object[] para) throws Exception {
        logger.info("test");
    }

    @BeforeSuite
    public void beforeSuite(ITestContext context) {
        for (ITestNGMethod testNGMethod : context.getAllTestMethods()) {
            Executor executor = (Executor) testNGMethod.getInstance();
            testNGMethod.setInvocationCount(executor.getTestData().getInvocationCount());
        }
    }

    /**
     * Testcase.
     *
     * @param testData   the test data
     * @param url        the url
     * @param httpMethod the http method
     */
    @Test(dataProvider = "data")
    public void testcase(TestData testData, String url, String httpMethod) {
        if (PropConfig.isSingle()) {
            ParamValueProcessor.processSingleTestdata(testData);
        } else {
            processSetupResultParam(testData);
        }
        processHeader(testData);
        String content = request(url, testData.getParams(), httpMethod, testData.isStoreCookie(), testData.isUseCookie(),testData.isAddParam());
        verifyResult(testData, content);

    }
}