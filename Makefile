.PHONY: dev test verify package lab-up lab-down cloud-up cloud-down cloud-seed

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

cloud-up:
	mvn package -DskipTests
	docker compose --profile cloud up --build -d

cloud-down:
	docker compose --profile cloud down

cloud-seed:
	bash src/main/docker/seed/seed-clouds.sh
