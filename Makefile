.PHONY: dev test verify package

dev:
	mvn quarkus:dev

test:
	mvn test

verify:
	mvn verify

package:
	mvn package
