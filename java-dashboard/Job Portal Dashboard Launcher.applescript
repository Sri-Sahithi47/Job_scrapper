set projectRoot to "/Users/srisahithiperiketi/Desktop/jobScrapper/jobPortals_scrapper"
set backendDir to projectRoot & "/java-dashboard/backend"
set frontendDir to projectRoot & "/java-dashboard/frontend"
set mavenBin to projectRoot & "/java-dashboard/apache-maven-3.9.9/bin/mvn"
set dashboardUrl to "http://127.0.0.1:5173"

set backendRunning to false
set frontendRunning to false

try
	do shell script "/usr/sbin/lsof -iTCP:8766 -sTCP:LISTEN -n -P >/dev/null 2>&1"
	set backendRunning to true
end try

try
	do shell script "/usr/sbin/lsof -iTCP:5173 -sTCP:LISTEN -n -P >/dev/null 2>&1"
	set frontendRunning to true
end try

tell application "Terminal"
	if backendRunning is false then
		do script "cd " & quoted form of backendDir & " && " & quoted form of mavenBin & " spring-boot:run"
	end if
	if frontendRunning is false then
		do script "cd " & quoted form of frontendDir & " && npm run dev"
	end if
end tell

repeat 45 times
	try
		do shell script "/usr/bin/curl -sSf http://127.0.0.1:8766/api/config >/dev/null 2>&1 && /usr/bin/curl -sSf " & dashboardUrl & " >/dev/null 2>&1"
		exit repeat
	end try
	delay 1
end repeat

open location dashboardUrl
