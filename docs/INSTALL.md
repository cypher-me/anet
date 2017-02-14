# ANET Installation Documentation

## Scope
This document covers the steps required to build the ANET platform from source and deploy to a server environment.  

## Environment

- **Hardware**: ANET does not have specific required hardware. Hardware recommendations are:
	- 1x Windows Application Server (300GB HDD, 64 GB RAM, 8x CPU Cores)
	- 1x Microsoft SQL Server (2014 or greater) Database Server. 
- **Software**: Software requirements: 
	- Java JRE 1.8 installed on the Application Server
	- Administration Privileges to run processes on restricted ports (80/443)
	- Optional: A valid SSL certificate for the domain name of the application server. 
	- Microsoft SQL Server 2014 or greater. 
	- Users are required to have a modern web browser (Mozilla Firefox, Google Chrome, or IE version 11 or greater)
- **Network Accessibility**
	- Users will acccess the Application Server over HTTP/HTTPS (80/443)
	- The Application Server will access the SQL Server over port 1433 (or whatever port you have SQL configured to)
	- The Application Server will need to access an Active Directory server for authentication
	- The Application Server will need to access an SMTP server for email sending. 


## Installation Prerequisites

There is no software to install on client computers, only a modern web browser (Mozilla Firefox, Google Chrome, or Microsoft IE Version 11 or greater) is required. 

You should have the following information on hand for the installation:
- Microsoft SQL Server:  Your Database Administrator should be able to provide you with these settings.  Just ask for an empty database. If you have access to your SQL Server directly, the command to create an empty database is `CREATE DATABASE database_name_here` 
	- hostname
	- username / password
	- database name
- SMTP Server
	- hostname
	- username / password (if necessary)
	- TLS settings (yes/no)
- Fully Qualified Domain Name of your server. 
- Information about who will Administer your ANET instance. 

## How to build ANET from Source
ANET must be build in two parts, the client, then the server: 

Linux/Mac:
```
cd client/
npm run build  # Builds the client
cd ../
./gradlew distZip  # Builds the client, server, and all dependencies into a single .zip file 
```

Windows:
```
cd client\
node.exe scripts\build.js  # Builds the client
cd ..\
gradlew.bat distZip  # Builds the client, server, and all dependencies into a single .zip file 
```

This will create a file in `build/distributions/anet.zip` which contains all the necessary files to install ANET. 

## Server Installation Procedures
Pick a directory on your server to install ANET to. In that directory: 

1. Unzip anet.zip
2. Add an anet.yml file with appropiate settings.  Descriptions of each of the settings in anet.yml can be found in the README.md file in the ANET repository. 
3. Install Database Schema: Run `bin/anet.bat db migrate anet.yml`
4. Seed the Database: Run `bin/anet.bat init anet.yml`.  This will ask you the following questions:
  1. _Classification String_: This is the message that will appear in the top security banner on the screen. For demo instances you should use `FOR DEMO USE ONLY`. 
  1. _Classification Color_ : This is the color of the top security banner on the screen. For demo instances you should use `green`. 
  1. _Name of Administrator Organization_: This is the name of the Organization that will be created for the Administrator.  We recommend using something like `ANET Administrators`.
  1. _Name of Administrator Position_: This is the name of the position that will be created for the Administrator.  We recommend `ANET Administrator`.
  1. _Your Name_: This is the name that will be given to the ANET Administrator, who you presumabely are. 
  1. _Your Domain Username_: This is the domain username that will be set on the ANET Administrator (who you presumabely are).  For production situations this will be your windows domain username.   If you get this wrong here, when you first log in to ANET it will create a new user for you. You can either run this database init command again, or do manual SQL commands to fix the `people` table.
5. Launch the ANET Server: `bin/anet.bat server anet.yml`

# ANET Upgrade Documentation
The steps to upgrade ANET across a minor version change are much simpler: 
To build the new anet.jar: 
- In the `client` directory run `npm run build` to build the ANET client package. 
- run `./gradlew.bat jar` to build the ANET server
- Find `anet.jar` in the `build/libs/` directory. 

On the ANET server: 
- Stop the `anet.bat server` process. 
- Take a complete backup of your SQL Database
- Backup the `anet.jar` file from the libs/ directory. 
- Copy the new `anet.jar` file into the libs/ directory. 
- Make any required changes or upgrades to your `anet.yml` file. 
- If Database Migrations are required, run `bin/anet.bat db migrate anet.yml` to migrate your database. 
- Start the server with `anet.bat server anet.yml`
- Run through verification testing to ensure there are no issues. 

# How to Rollback an ANET Upgrade. 

# How to enable SSL
It is recommended that you enable SSL support on ANET.  To do so, follow the Dropwizard Documentation here: http://www.dropwizard.io/1.0.5/docs/manual/core.html#ssl 

# How to Configure a local imagery cache