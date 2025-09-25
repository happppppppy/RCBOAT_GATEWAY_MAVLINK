# RC Boat MAVLink Gateway

A production-quality Android application that transforms an Android phone into a MAVLink companion computer and router for STM32-based RC boats.

## Overview

This app provides:
- **USB Serial Communication**: Direct connection to STM32 board running MAVLink at 57600 baud
- **Cloud Connectivity**: Bidirectional forwarding to cloud relay via TLS (TCP or WebSocket)
- **Local UDP Mirror**: Optional forwarding to local QGroundControl for debugging  
- **Sensor Injection**: Phone GPS, IMU, and battery data as MAVLink messages
- **Robust Reconnection**: Automatic retry with exponential backoff
- **Headless Operation**: Foreground service maintains connections when app is backgrounded
- **Security**: Optional MAVLink 2 signing with configurable keys

## Architecture

```
┌─────────────┐    USB Serial    ┌─────────────┐    Cloud Link    ┌─────────────┐
│   STM32     │ ←─────────────→  │   Android   │ ←─────────────→  │   Cloud     │
│   Board     │      57600       │   Gateway   │   TLS/WSS        │   Relay     │
└─────────────┘                  └─────────────┘                  └─────────────┘
                                        │
                                        ├─── Sensor Injection (GPS, IMU, Battery)
                                        │
                                        └─── UDP Mirror (Optional)
                                                    │
                                              ┌─────────────┐
                                              │    QGC      │
                                              │  (Local)    │
                                              └─────────────┘
```

### Core Components

- **RouterEngine**: Main orchestrator for bidirectional MAVLink forwarding
- **Transport Layer**: USB Serial, TCP/TLS, WebSocket, UDP Mirror managers  
- **Sensor Providers**: GPS, IMU, Battery data injection
- **Configuration**: Persistent settings via DataStore
- **Security**: MAVLink 2 signing manager
- **UI**: Compose-based status dashboard and settings

## Features

### Transport Options
- **TCP with TLS**: Secure TCP socket connection
- **WebSocket with TLS**: Secure WebSocket connection
- Runtime selectable transport type

### Sensor Integration
- **GPS**: Android location services → GPS_INPUT messages
- **IMU**: Accelerometer, Gyroscope, Magnetometer → HIGHRES_IMU messages
- **Battery**: Phone battery status → BATTERY_STATUS messages
- Configurable injection rates (0.1-50 Hz)

### Connection Management
- Exponential backoff with jitter (500ms - 30s)
- USB detach/attach detection
- Cellular connectivity monitoring
- Automatic service restart on configuration changes

### Security Features
- MAVLink 2 message signing (compile-time configurable)
- 32-byte hex key configuration
- Rolling timestamp and signature validation

## Setup Instructions

### Prerequisites
- Android device with USB OTG support
- Android 8.0+ (API 26+)
- STM32 board with MAVLink firmware
- USB OTG cable

### Build Instructions

1. **Clone Repository**
   ```bash
   git clone https://github.com/happppppppy/RCBOAT_GATEWAY_MAVLINK.git
   cd RCBOAT_GATEWAY_MAVLINK
   ```

2. **Build APK**
   ```bash
   ./gradlew assembleRelease
   ```

3. **Install on Device**
   ```bash
   adb install app/build/outputs/apk/release/app-release.apk
   ```

### Configuration

1. **Launch App**: Grant location permissions when prompted
2. **Connect USB**: Attach STM32 board via USB OTG
3. **Configure Settings**:
   - Cloud Host/Port
   - Transport Type (TCP TLS / WebSocket TLS)
   - UDP Mirror (optional)
   - Sensor rates and enable flags
   - MAVLink signing (optional)

4. **Start Service**: Tap "Start Service" to begin routing

### Permissions Required
- `INTERNET`: Cloud connectivity
- `ACCESS_FINE_LOCATION`: GPS sensor data
- `FOREGROUND_SERVICE`: Background operation
- USB device access (granted via system dialog)

## Configuration Parameters

### Connectivity
| Parameter | Default | Description |
|-----------|---------|-------------|
| `cloudHost` | `mavlink.example.com` | Cloud relay hostname |
| `cloudPort` | `5760` | Cloud relay port |
| `transportType` | `TCP_TLS` | Transport: TCP_TLS or WEBSOCKET_TLS |
| `mavlinkBaud` | `57600` | USB serial baud rate |

### UDP Mirror
| Parameter | Default | Description |
|-----------|---------|-------------|
| `secondaryUdpEnabled` | `false` | Enable UDP mirroring |
| `secondaryUdpHost` | `192.168.1.100` | Target IP address |
| `secondaryUdpPort` | `14550` | Target UDP port (QGC default) |

### Sensors
| Parameter | Default | Description |
|-----------|---------|-------------|
| `sensorGpsRateHz` | `1.0` | GPS injection rate (0.1-5 Hz) |
| `sensorImuRateHz` | `10.0` | IMU injection rate (1-50 Hz) |
| `sensorBatteryRateHz` | `0.5` | Battery injection rate (0.1-2 Hz) |

### Security
| Parameter | Default | Description |
|-----------|---------|-------------|
| `signingEnabled` | `false` | Enable MAVLink 2 signing |
| `signingKeyHex` | `""` | 32-byte signing key (hex) |

### Advanced
| Parameter | Default | Description |
|-----------|---------|-------------|
| `reconnectBaseMs` | `1000` | Base reconnection delay |
| `reconnectMaxMs` | `30000` | Maximum reconnection delay |
| `logLevel` | `INFO` | Timber log level |

## Usage

### Normal Operation
1. Connect STM32 board to Android device via USB
2. Start the MAVLink service
3. Monitor connection status in the app
4. View statistics (uplink/downlink bytes, frame counts)

### Troubleshooting
- Check USB connection indicator
- Verify cloud connectivity with "Test Connection"
- Monitor logs for detailed error information
- Restart service if connections become unstable

## Development

### Project Structure
```
app/src/main/java/com/rcboat/gateway/mavlink/
├── data/
│   ├── config/          # Configuration models and repository
│   ├── mavlink/         # MAVLink codec and frame definitions
│   └── transport/       # USB, TCP, WebSocket, UDP managers
├── domain/
│   ├── routing/         # Router engine and backoff strategy
│   └── sensors/         # GPS, IMU, Battery providers
├── service/             # Foreground service
├── security/            # MAVLink signing manager
├── ui/                  # Compose UI screens and components
├── di/                  # Hilt dependency injection
└── util/                # Result wrappers and extensions
```

### Key Libraries
- **USB Serial**: `usb-serial-for-android` for STM32 communication
- **MAVLink**: `io.dronefleet.mavlink` for message handling
- **Network**: `OkHttp`/`Okio` for cloud connectivity
- **UI**: Jetpack Compose with Material 3
- **DI**: Hilt for dependency injection
- **Persistence**: DataStore for configuration
- **Logging**: Timber for structured logging

### Testing
```bash
./gradlew test                    # Unit tests
./gradlew connectedAndroidTest    # Instrumentation tests
```

## Extensibility

The architecture supports future enhancements:

### Video Streaming
- Add `VideoStreamManager` to transport layer
- Integrate WebRTC or RTMP streaming
- Hook into router engine for stream control

### Mission Management  
- Implement `MissionManager` for waypoint handling
- Add mission upload/download UI screens
- Integrate with MAVLink mission protocol

### Authentication
- Add `AuthInterceptor` to validate command sources
- Implement token-based authentication
- Rate limiting and access control

### Message Filtering
- Add `MessageFilter` chain to router engine
- Configurable allow/deny rules by message type
- Priority-based message queuing

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes with tests
4. Submit a pull request

## Support

For issues and questions:
- Create GitHub issues for bugs and feature requests  
- Include device model, Android version, and logs
- Provide steps to reproduce any problems