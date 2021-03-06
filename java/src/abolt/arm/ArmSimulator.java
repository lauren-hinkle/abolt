package abolt.arm;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Timer;
import java.util.TimerTask;

import abolt.arm.BoltArmController.ActionMode;
import abolt.bolt.Bolt;
import abolt.bolt.IBoltGUI;
import abolt.lcmtypes.bolt_arm_command_t;
import abolt.lcmtypes.observations_t;
import abolt.lcmtypes.robot_action_t;
import abolt.lcmtypes.robot_command_t;
import abolt.objects.BoltObject;
import abolt.util.SimUtil;
import april.jmat.LinAlg;
import april.util.TimeUtil;
import april.vis.VisChain;
import april.vis.VisObject;
import april.vis.VzCircle;
import april.vis.VzMesh;

import lcm.lcm.LCM;
import lcm.lcm.LCMDataInputStream;
import lcm.lcm.LCMSubscriber;

public class ArmSimulator implements LCMSubscriber{
	private ActionMode curState = ActionMode.WAIT;

	private static LCM lcm = LCM.getSingleton();
	
	private static final int UPDATE_RATE = 10;	// # updates per second
	private static final int GRABBING_TIME = UPDATE_RATE*3;
	private static final int POINTING_TIME = UPDATE_RATE*2;
	private static final int DROPPING_TIME = UPDATE_RATE;
	private Timer updateTimer;
	
    Queue<robot_command_t> cmds = new LinkedList<robot_command_t>();
	
	
	double[] pos;
	double[] goal;
	private int grabbedID = -1;
	int stepsLeft = 0;
	
	
	public ArmSimulator(){
        lcm.subscribe("ROBOT_COMMAND", this);
        
        pos = new double[]{0, 0, 0.001};
        goal = new double[]{0, 0};
        
		class UpdateTask extends TimerTask{
			public void run() {
				update();
			}
    	}
		updateTimer = new Timer();
		updateTimer.schedule(new UpdateTask(), 1000, 1000/UPDATE_RATE);
	}
	
	public void update(){
		if(curState == ActionMode.WAIT){
			if(!cmds.isEmpty()){
				executeCommand(cmds.poll());
			}
		} else {
			if(--stepsLeft == 0){
				curState = ActionMode.WAIT;
				if(curState == ActionMode.DROP){
					grabbedID = -1;
				}
			} else {
				pos[0] += (goal[0] - pos[0])/stepsLeft;
				pos[1] += (goal[1] - pos[1])/stepsLeft;
			}
		}
		if(grabbedID != -1 && curState != ActionMode.GRAB){
			HashMap<Integer, BoltObject> objects = Bolt.getObjectManager().getObjects();
			BoltObject obj;
			synchronized(objects){
				obj = objects.get(grabbedID);
			}
			if(obj != null){
				double[] objPos = obj.getPos();
				objPos[0] = pos[0];
				objPos[1] = pos[1];
				obj.setPos(objPos);
			}
		}
		
		sendStatusUpdate();
		drawArm();
	}
	
	private void drawArm(){
		IBoltGUI gui = Bolt.getBoltGUI();
		ArrayList<VisObject> visObjs = new ArrayList<VisObject>();
		visObjs.add(new VisChain(LinAlg.translate(pos), LinAlg.scale(.1), new VzCircle(new VzMesh.Style(Color.black))));
		if(grabbedID != -1 && curState != ActionMode.GRAB){
			visObjs.add(new VisChain(LinAlg.translate(new double[]{pos[0], pos[1], .001}), LinAlg.scale(.09), new VzCircle(new VzMesh.Style(Color.cyan))));
		}
		gui.drawVisObjects("arm", visObjs);
	}
	
	private void executeCommand(robot_command_t command){
		String action = command.action;
		if(action.contains("POINT")){
			curState = ActionMode.POINT;
			goal[0] = command.dest[0];
			goal[1] = command.dest[1];
			stepsLeft = POINTING_TIME;
		} else if(action.contains("GRAB")){
			int id = Integer.parseInt(SimUtil.getTokenValue(action, "GRAB"));
			HashMap<Integer, BoltObject> objects = Bolt.getObjectManager().getObjects();
			BoltObject obj;
			synchronized(objects){
				obj = objects.get(id);
			}
			if(obj == null){
				return;
			}
			curState = ActionMode.GRAB;
			goal[0] = obj.getPos()[0];
			goal[1] = obj.getPos()[1];
			grabbedID = id;
			stepsLeft = GRABBING_TIME;	
		} else if(action.contains("DROP")){
			curState = ActionMode.DROP;
			goal[0] = command.dest[0];
			goal[1] = command.dest[1];
			stepsLeft = DROPPING_TIME;
		} else if(action.contains("RESET")){
			curState = ActionMode.POINT;
			goal[0] = 0;
			goal[1] = 0;
			grabbedID = -1;
			stepsLeft = POINTING_TIME;
		}
	}
	
	@Override
	public void messageReceived(LCM lcm, String channel, LCMDataInputStream ins) {
		if(channel.equals("ROBOT_COMMAND")){
        	try{
        		robot_command_t command = new robot_command_t(ins);
        		cmds.add(command);
        	}catch (IOException e) {
                e.printStackTrace();
                return;
            }
		}
	}
	
	private void sendStatusUpdate(){
		robot_action_t status = new robot_action_t();
		status.utime = TimeUtil.utime();
		switch(curState){
		case POINT:
			status.action = "POINT";
			break;
		case GRAB:
			status.action = "GRAB";
			break;
		case DROP:
			status.action = "DROP";
			break;
		default:
			status.action = "WAIT";
			break;
		}
		if(grabbedID != -1 && curState != ActionMode.GRAB){
			status.obj_id = grabbedID;
		} else {
			status.obj_id = -1;
		}
		status.xyz = pos.clone();

        lcm.publish("ROBOT_ACTION", status);
	}
}
