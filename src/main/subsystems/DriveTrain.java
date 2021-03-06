package main.subsystems;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;

import com.ctre.CANTalon.FeedbackDevice;
import com.ctre.CANTalon.TalonControlMode;
import com.kauailabs.navx.frc.AHRS;//NavX import
import Util.DriveHelper;
import Util.MathHelper;
import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.PIDController;
import edu.wpi.first.wpilibj.PIDOutput;
import edu.wpi.first.wpilibj.PIDSource;
import edu.wpi.first.wpilibj.PIDSourceType;
import edu.wpi.first.wpilibj.RobotDrive;
import edu.wpi.first.wpilibj.SPI;
import edu.wpi.first.wpilibj.command.Subsystem;
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard;
import lib.AutoTune;
import main.Constants;
import main.HardwareAdapter;
import main.Robot;
import main.commands.drivetrain.Drive;
import main.commands.pnuematics.ShiftDown;

public class DriveTrain extends Subsystem implements Constants, HardwareAdapter {
	private static boolean highGearState = false;
	private static AHRS NavX;
	private DriveHelper helper = new DriveHelper(7.5);
	private static RobotDrive driveTrain = new RobotDrive(leftDriveMaster, rightDriveMaster);
	private double smallTurnControllerRate, bigTurnControllerRate, distanceControllerRate;
	private PIDController smallTurnController;
	private PIDController bigTurnController;
	private PIDController distanceController;
	private boolean smallAngleIsStable = false, bigAngleIsStable = false, distanceIsStable = false;
	private MathHelper smallAngleHelper, bigAngleHelper, distanceHelper, angularRateOfChangeHelper, distanceRateOfChangeHelper;
	private double angularRateOfChange, distanceRateOfChange;
	private double autoTuneStep = 50.0, autoTuneNoise = 1.0;
	private int autoTuneLookBack = 20;
	private AutoTune autoTune;
	private double tunedSmallKP, tunedSmallKI, tunedSmallKD, tunedBigKP, tunedBigKI, tunedBigKD, 
					tunedDistanceKP, tunedDistanceKI, tunedDistanceKD;
	//private DatagramSocket serverSocket;
	//private byte[] sendData;
	//private  DatagramPacket sendPacket;

	public DriveTrain() {
		smallAngleHelper = new MathHelper();
		bigAngleHelper = new MathHelper();
		distanceHelper = new MathHelper();
		setTalonDefaults();
		//try {
			//serverSocket = new DatagramSocket(udpPortForLogging);
		//} catch (SocketException e) {
			// TODO Auto-generated catch block
			//e.printStackTrace();
		//}

		try {
			/* Communicate w/navX-MXP via the MXP SPI Bus. */
			/*
			 * Alternatively: I2C.Port.kMXP, SerialPort.Port.kMXP or
			 * SerialPort.Port.kUSB
			 */
			/*
			 * See
			 * http://navx-mxp.kauailabs.com/guidance/selecting-an-interface/
			 * for details.
			 */
			NavX = new AHRS(SPI.Port.kMXP);
		} catch (RuntimeException ex) {
			DriverStation.reportError("Error instantiating navX-MXP:  " + ex.getMessage(), true);
		}
		resetSensors();// Must happen after NavX is instantiated!

		smallTurnController = new PIDController(turnInPlaceKPSmallAngle, turnInPlaceKISmallAngle,
				turnInPlaceKDSmallAngle, NavX, new PIDOutput() {
					public void pidWrite(double d) {
						smallTurnControllerRate = d + (kMinVoltageTurnSmallAngle * Math.signum(d)) / 10;
					}
				});

		bigTurnController = new PIDController(turnInPlaceKPBigAngle, turnInPlaceKIBigAngle, turnInPlaceKDBigAngle, NavX,
				new PIDOutput() {
					public void pidWrite(double d) {
						bigTurnControllerRate = d + (kMinVoltageTurnBigAngle * Math.signum(d)) / 10;
					}
				});

		distanceController = new PIDController(displacementKP, displacementKI, displacementKD, new PIDSource() {
			PIDSourceType m_sourceType = PIDSourceType.kDisplacement;

			public double pidGet() {
				return (getDistanceTraveledRight());
			}

			public void setPIDSourceType(PIDSourceType pidSource) {
				m_sourceType = pidSource;
			}

			public PIDSourceType getPIDSourceType() {
				return m_sourceType;
			}
		}, new PIDOutput() {
			public void pidWrite(double d) {
				distanceControllerRate = d;
			}
		});

	}
	
	public void driveVelocity(double throttle, double heading) {
		distanceRateOfChangeHelper = new MathHelper(this.getDistanceTraveledRight(), System.currentTimeMillis());
		angularRateOfChangeHelper = new MathHelper(NavX.getYaw(), System.currentTimeMillis());
		if(Robot.gameState == Robot.GameState.Autonomous || Robot.gameState == Robot.GameState.Teleop)
			driveTrain.arcadeDrive(helper.handleOverPower(throttle), helper.handleOverPower(heading));
		distanceRateOfChange = distanceRateOfChangeHelper.rateOfChange(getDistanceTraveledRight());
		angularRateOfChangeHelper = new MathHelper(NavX.getYaw(), System.currentTimeMillis());
		updateRobotState();
		//sendDriveBaseDataOverUDP();
	}
	
	public void driveStraight(double throttle) {
		double theta = NavX.getYaw();
		distanceRateOfChangeHelper = new MathHelper(this.getDistanceTraveledRight(), System.currentTimeMillis());
		angularRateOfChangeHelper = new MathHelper(NavX.getYaw(), System.currentTimeMillis());
		if(Math.signum(throttle) > 0) {
			//Make this PID Controlled
			driveTrain.arcadeDrive(helper.handleOverPower(throttle), helper.handleOverPower(theta * straightLineKP)); 
		}
		else {
			//Might be unnecessary but I think the gyro bearing changes if you drive backwards
			driveTrain.arcadeDrive(helper.handleOverPower(throttle), helper.handleOverPower(theta * straightLineKPReverse)); 
		}
		distanceRateOfChange = distanceRateOfChangeHelper.rateOfChange(getDistanceTraveledRight());
		angularRateOfChangeHelper = new MathHelper(NavX.getYaw(), System.currentTimeMillis());
		updateRobotState();
		//sendDriveBaseDataOverUDP();
	}
	
	public void driveDistanceSetPID(double p, double i, double d, double maxV, boolean tuning) {
		distanceController.setPID(p, i, d);
		distanceController.setOutputRange(-maxV/10, maxV/10);
		distanceRateOfChangeHelper = new MathHelper(this.getDistanceTraveledRight(), System.currentTimeMillis());
		if(tuning) {
			autoTune = new AutoTune();
			autoTune.setNoiseBand(autoTuneNoise);
			autoTune.setOutputStep(autoTuneStep);
			autoTune.setLookbackSec((int) autoTuneLookBack);

		}
	}
	
	public void driveDistance(double distance, double tolerance, boolean tuning) {
		if(highGearState)
			new ShiftDown();
		setBrakeMode(true);
		setCtrlMode(PERCENT_VBUS_MODE);
		//setVoltageDefaultsPID();
		
		distanceController.setInputRange(-20.0, +20.0);
		distanceController.setAbsoluteTolerance(tolerance);
		distanceController.setContinuous(true);
		distanceController.enable();
		distanceController.setSetpoint(distance);
		//System.out.println("r" + distanceControllerRate);
		this.driveVelocity(distanceControllerRate, 0.0);//Gyro code in drive straight I think is messed up
		distanceIsStable = distanceHelper.isStable(distance, getDistanceTraveledRight(), tolerance);
		distanceRateOfChange = distanceRateOfChangeHelper.rateOfChange(getDistanceTraveledRight());
		if(tuning) {
			if (autoTune.runtime((double) Robot.dt.getDistanceTraveledRight(), distanceControllerRate)) {
				autoTune.cancel();
				tunedDistanceKP = autoTune.getKp();
				tunedDistanceKI = autoTune.getKi();
				tunedDistanceKD = autoTune.getKd();
			}
		}
		updateRobotState();
		//sendDriveBaseDataOverUDP();
		
	}
	
	@SuppressWarnings("deprecation")
	public void turnToBigAngleSetPIDMinVoltage(double minV) {
		SmartDashboard.putDouble("Turning MinVoltage Big Angle", minV);

	}
	
	public void turnToBigAngleSetPID(double p, double i, double d, double maxV, boolean tuning) {
		@SuppressWarnings("deprecation")
		double minVoltage = SmartDashboard.getDouble("Turning MinVoltage Big Angle", 0.0);
		maxV = 10.5;
		bigTurnController.setPID(p, i, d);
		System.out.println(-(maxV-minVoltage)/10 + " " + (maxV-minVoltage)/10);
		bigTurnController.setOutputRange(-(maxV-minVoltage)/10, (maxV-minVoltage)/10);
		angularRateOfChangeHelper = new MathHelper(NavX.getYaw(), System.currentTimeMillis());
		if(tuning) {
			autoTune = new AutoTune();
			autoTune.setNoiseBand(autoTuneNoise);
			autoTune.setOutputStep(autoTuneStep);
			autoTune.setLookbackSec((int) autoTuneLookBack);

		}
	}
	
	public void turnToBigAngle(double heading, double tolerance, boolean tuning) {
		if(highGearState)
			new ShiftDown();
		setBrakeMode(true);
		setCtrlMode(PERCENT_VBUS_MODE);
				
		bigTurnController.setInputRange(-180.0f,  180.0f);
		bigTurnController.setAbsoluteTolerance(tolerance);
		bigTurnController.setContinuous(true);
		bigTurnController.enable();
		bigTurnController.setSetpoint(heading);
		this.driveVelocity(0.0, bigTurnControllerRate);
		bigAngleIsStable = bigAngleHelper.isStable(heading, NavX.getYaw(), tolerance);
		angularRateOfChange = angularRateOfChangeHelper.rateOfChange(NavX.getYaw());
		if(tuning) {
			if (autoTune.runtime((double) Robot.dt.getGyro().getYaw(), bigTurnControllerRate)) {
				autoTune.cancel();
				tunedBigKP = autoTune.getKp();
				tunedBigKI = autoTune.getKi();
				tunedBigKD = autoTune.getKd();
			}
		}
		updateRobotState();
		//sendDriveBaseDataOverUDP();
	}
	
	@SuppressWarnings("deprecation")
	public void turnToSmallAngleSetPIDMinVoltage(double minV) {
		SmartDashboard.putDouble("Turning MinVoltage Small Angle", minV);
	}
	
	public void turnToSmallAngleSetPID(double p, double i, double d, double maxV, boolean tuning) {
		@SuppressWarnings("deprecation")
		double minVoltage = SmartDashboard.getDouble("Turning MinVoltage Small Angle", 0.0);
		maxV = 9.0;
		smallTurnController.setPID(p, i, d);
		System.out.println(-(maxV-minVoltage)/10 + " " + (maxV-minVoltage)/10);
		smallTurnController.setOutputRange(-(maxV-minVoltage)/10, (maxV-minVoltage)/10);
		angularRateOfChangeHelper = new MathHelper(NavX.getYaw(), System.currentTimeMillis());
		if(tuning) {
			autoTune = new AutoTune();
			autoTune.setNoiseBand(autoTuneNoise);
			autoTune.setOutputStep(autoTuneStep);
			autoTune.setLookbackSec((int) autoTuneLookBack);

		}

	}
	
	public void turnToSmallAngle(double heading, double tolerance, boolean tuning) {
		if(highGearState)
			new ShiftDown();
		setBrakeMode(true);
		setCtrlMode(PERCENT_VBUS_MODE);
				
		smallTurnController.setInputRange(-180.0f,  180.0f);
		smallTurnController.setAbsoluteTolerance(tolerance);
		smallTurnController.setContinuous(true);
		smallTurnController.enable();
		smallTurnController.setSetpoint(heading);
		this.driveVelocity(0.0, smallTurnControllerRate);
		smallAngleIsStable = smallAngleHelper.isStable(heading, NavX.getYaw(), tolerance);
		angularRateOfChange = angularRateOfChangeHelper.rateOfChange(NavX.getYaw());
		if(tuning) {
			if (autoTune.runtime((double) Robot.dt.getGyro().getYaw(), smallTurnControllerRate)) {
				autoTune.cancel();
				tunedSmallKP = autoTune.getKp();
				tunedSmallKI = autoTune.getKi();
				tunedSmallKD = autoTune.getKd();
			}
		}
		updateRobotState();
		//sendDriveBaseDataOverUDP();
	}
	
	public double getDistanceAvg() {
		return (getDistanceTraveledLeft() + getDistanceTraveledRight())/2;
	}
	
	public void changeGearing(){
		highGearState = !highGearState;
	}
	
	public AHRS getGyro(){
		return NavX;
	}
	
	public double getSmallAnglePIDControllerRate() {
		return smallTurnControllerRate;
	}
	
	public double getBigAnglePIDControllerRate() {
		return bigTurnControllerRate;
	}
	
	public double getDistancePIDControllerRate() {
		return distanceControllerRate;
	}
	
	public boolean getSmallAngleIsStable() {
		return smallAngleIsStable;
	}
	
	public boolean getBigAngleIsStable() {
		return bigAngleIsStable;
	}
	
	public boolean getDistanceIsStable() {
		return distanceIsStable;
	}
	
	public double getAngularRateOfChange() {
		return angularRateOfChange;
	}
	
	public double getDistanceRateOfChange() {
		return distanceRateOfChange;
	}
	
	public double getTunedSmallAngleKP() {
		return tunedSmallKP;
	}

	public double getTunedSmallAngleKI() {
		return tunedSmallKI;
	}

	public double getTunedSmallAngleKD() {
		return tunedSmallKD;
	}

	public double getTunedBigAngleKP() {
		return tunedBigKP;
	}

	public double getTunedBigAngleKI() {
		return tunedBigKI;
	}

	public double getTunedBigAngleKD() {
		return tunedBigKD;
	}
	
	public double getTunedDistanceKP() {
		return tunedDistanceKP;
	}
	
	public double getTunedDistanceKI() {
		return tunedDistanceKI;
	}
	
	public double getTunedDistanceKD() {
		return tunedDistanceKD;
	}

	public int convertToEncoderTicks(double displacement) {//ft
		return (int) (((displacement / (wheelSize*Math.PI)) * conversionFactor));
	}
	
	public double getDistanceTraveledLeft() {//Feet
		return wheelSize*Math.PI*(getLeftEncoderPosition()/conversionFactor);
	}
	
	public double getDistanceTraveledRight() {//Feet
		//Removed - value and changed with reverseSensor() so that pid has correct feedback
		//System.out.println("r" +wheelSize*Math.PI*(getRightEncoderPosition()/conversionFactor));
		return wheelSize*Math.PI*(getRightEncoderPosition()/conversionFactor);
	}
	
	public double getLeftVelocity() {
		return leftDriveMaster.getEncVelocity() / wheelEncoderMult;
	}
	
	public double getRightVelocity() {
		return rightDriveMaster.getEncVelocity() / wheelEncoderMult;
	}
	
	public void resetGyro() {
		NavX.reset();
		NavX.zeroYaw();
	}
	public void resetEncoders() {
		leftDriveMaster.setEncPosition(0);//I'm gay
		rightDriveMaster.setEncPosition(0);//I'm gay
		leftDriveMaster.setPosition(0);
		rightDriveMaster.setPosition(0);
	}
	
	public void resetSensors() {
		resetGyro();
		resetEncoders();
	}
	
	/*******************
	 * SUPPORT METHODS *
	 *******************/
	private void updateRobotState() {
		if(rightDriveMaster.getOutputVoltage() > -0.1 && rightDriveMaster.getOutputVoltage() < 0.1 
				&& leftDriveMaster.getOutputVoltage() > -0.1 && leftDriveMaster.getOutputVoltage() < 0.1 
				&& Robot.robotState != Robot.RobotState.Climbing)	
			Robot.robotState = Robot.RobotState.Neither;
	}
	
	/*private void sendDriveBaseDataOverUDP() {
		String data = System.currentTimeMillis() + ", " + leftDriveMaster.getOutputVoltage() + ", " + rightDriveMaster.getOutputVoltage() 
		 + ", " + getDistanceTraveledLeft() + ", " + getDistanceTraveledRight() + ", " + NavX.getYaw();
		sendData = data.getBytes();
		try {
			sendPacket = new DatagramPacket(sendData, sendData.length, InetAddress.getByName(kangarooIP), udpPort);
		} catch (UnknownHostException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		try {
			serverSocket.send(sendPacket);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}*/
	
	private double getLeftEncoderPosition() {
		return leftDriveMaster.getEncPosition();
	}
	
	private double getRightEncoderPosition() {
		return rightDriveMaster.getEncPosition();
	}
	
	/**
	 * Reverses the output of the Talon SRX's
	 * 
	 * @param output - Whether the output should be reversed.
	 */
	private void reverseTalons(boolean output) {//Actually Works ?
		leftDriveMaster.reverseOutput(output);
		rightDriveMaster.reverseOutput(output);
	}

	/**
	 * Sets the Talon SRX's brake mode
	 * 
	 * @param brake - Sets the brake mode (Uses default brake modes)
	 */
	private void setBrakeMode(Boolean brake) {
		leftDriveMaster.enableBrakeMode(brake);
		leftDriveSlave1.enableBrakeMode(brake);
		//leftDriveSlave2.enableBrakeMode(brake);
		rightDriveMaster.enableBrakeMode(brake);
		rightDriveSlave1.enableBrakeMode(brake);
		//rightDriveSlave2.enableBrakeMode(brake);
	}

	/**
	 * Sets the Talon SRX's control mode
	 * 
	 * @param mode - Sets the control mode (Uses default control modes)
	 */
	private void setCtrlMode(TalonControlMode mode) {
		leftDriveMaster.changeControlMode(mode);
		leftDriveSlave1.changeControlMode(SLAVE_MODE);
		leftDriveSlave1.set(leftDriveMaster.getDeviceID());
		//leftDriveSlave2.changeControlMode(SLAVE_MODE);
		//leftDriveSlave2.set(leftDriveMaster.getDeviceID());
		
		rightDriveMaster.changeControlMode(mode);
		rightDriveSlave1.changeControlMode(SLAVE_MODE);
		rightDriveSlave1.set(rightDriveMaster.getDeviceID());
		//rightDriveSlave2.changeControlMode(SLAVE_MODE);
		//rightDriveSlave2.set(rightDriveMaster.getDeviceID());
	}
	
	/**
	 * Set's the Talon SRX's feedback device
	 * 
	 */
	private void setFeedBackDefaults() {
		leftDriveMaster.setFeedbackDevice(FeedbackDevice.QuadEncoder);
		rightDriveMaster.setFeedbackDevice(FeedbackDevice.QuadEncoder);
		leftDriveMaster.configEncoderCodesPerRev(codesPerRev);
		rightDriveMaster.configEncoderCodesPerRev(codesPerRev);
		leftDriveMaster.reverseSensor(true);//Check this later//was true
		rightDriveMaster.reverseSensor(true);//Check this later//was true
	}
	
	/**
	 * Sets the Talon SRX's voltage defaults (Serves to help keep the drivetrain consistent)
	 */
	private void setVoltageDefaults() {
		leftDriveMaster.configNominalOutputVoltage(+0f, -0f);
		rightDriveMaster.configNominalOutputVoltage(+0f, -0f);
		leftDriveMaster.configPeakOutputVoltage(+12f, -12f);
		rightDriveMaster.configPeakOutputVoltage(+12f, -12f);
	}
	
	/*private void setVoltageDefaultsPID() {
		leftDriveMaster.configNominalOutputVoltage(+0f, -0f);
		rightDriveMaster.configNominalOutputVoltage(+0f, -0f);
		leftDriveMaster.configPeakOutputVoltage(+6f, -6f);
		rightDriveMaster.configPeakOutputVoltage(+6f, -6f);
	}*/
	
	/**
	 * Sets the Talon SRX's voltage ramp rate (Smooth's acceleration (units in volts/sec))
	 */
	private void setRampRate(double ramp) {
		leftDriveMaster.setVoltageCompensationRampRate(ramp);
		rightDriveMaster.setVoltageCompensationRampRate(ramp);
	}

	/**
	 * Sets the Talon SRX's defaults (reversing, brake and control modes)
	 */
	private void setTalonDefaults() {
		setFeedBackDefaults();
		setVoltageDefaults();
		//setRampRate(12);//0-12v in 1 of a second //COMMENTED TO SEE IF THIS PREVENTS PID FROM FUNCTIONING
		reverseTalons(true);//Changing this didn't do anything, mathematically negated in drive command
		setBrakeMode(true);
		setCtrlMode(DEFAULT_CTRL_MODE);
	}
	
	@Override
	protected void initDefaultCommand() {
		setDefaultCommand(new Drive());
		
	}
		
}
