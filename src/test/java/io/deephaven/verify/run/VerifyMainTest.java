package io.deephaven.verify.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

public class VerifyMainTest {
	String[] args1 = {};
	String[] args2 = {"-n", "^(.*Test)$"};
	String[] args3 = {"--include-classname=^(.*Test)$"};
	String[] args4 = {"-p", "io.deephaven.stuff"};
	String[] args5 = {"--select-package=io.deephaven.stuff"};
	String[] args6 = {"-n", "^(.*Test)$", "-p", "io.deephaven.stuff"};
	String[] args7 = {"-n", "^(.*Test)$", "--select-package=io.deephaven.stuff"};
	
	@Test
	public void addDefaults() {
		assertDefaults("[-n, ^(.*)$, -p, io.deephaven.verify.tests]", args1);
		assertDefaults("[-n, ^(.*Test)$, -p, io.deephaven.verify.tests]", args2);
		assertDefaults("[--include-classname=^(.*Test)$, -p, io.deephaven.verify.tests]", args3);
		assertDefaults("[-p, io.deephaven.stuff, -n, ^(.*)$]", args4);
		assertDefaults("[--select-package=io.deephaven.stuff, -n, ^(.*)$]", args5);
		assertDefaults("[-n, ^(.*Test)$, -p, io.deephaven.stuff]", args6);
		assertDefaults("[-n, ^(.*Test)$, --select-package=io.deephaven.stuff]", args7);
	}
	
	void assertDefaults(String expected, String[] args) {
		String[] newArgs = VerifyMain.addDefaults(args);
		assertEquals(expected, Arrays.toString(newArgs), "Wrong arguments");
	}
	
}
