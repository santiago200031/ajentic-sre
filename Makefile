.PHONY: dev test verify package lab-up lab-down

dev:
	mvn quarkus:dev

test:
	mvn test

verify:
	mvn verify

package:
	mvn package

lab-up:
	mvn package -DskipTests
	docker compose up --build

lab-down:
	docker compose down
