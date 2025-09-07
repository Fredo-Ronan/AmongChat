# AmongChat

<p align="center">
  <img width="246" height="246" alt="ic_launcher-web" src="https://github.com/user-attachments/assets/c5d481ad-0eeb-449d-8705-b92d45321a55" />
</p>

<p align="center">
  <img src="https://github.com/Fredo-Ronan/AmongChat/actions/workflows/amongchat-ci.yml/badge.svg"/>
  <img src="https://img.shields.io/github/contributors/fredo-ronan/AmongChat"/>
  <img src="https://img.shields.io/github/last-commit/fredo-ronan/AmongChat"/>
</p>

An experimental live chat android app that rely only on bluetooth connection without internet required (works offline) that planned to have a bluetooth mesh mechanism using BLE (Bluetooth Low Energy)

## What are the features planned?
- Live chat session using bluetooth classic mode (host and client schema)
- Live chat session using bluetooth LE (Low Energy) for creating a bluetooth mesh <-- The main goal actually is this (experimental)

## Current Features
- Run a live chat session using bluetooth classic mode (host and client schema) with some bugs or not perfect yet

## Bugs?
- There is probably a false algorithm logic when broadcasting the message to other connected clients on live chat session with bluetooth classic (host + client schema) so that one or more clients are not receiving the message sent by other client or the host it self
