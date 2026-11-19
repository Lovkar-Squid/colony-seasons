"""Blender side of the design renderer (run: blender --background --factory-startup --python render_scene.py -- jobs.json).

Reads quad scenes written by mcmodel.write_scene and renders them the way the vanilla client
draws them in daylight: every face is unlit texture colour x (vanilla face shade x smooth-lighting
AO), nearest-neighbour texels, cutout alpha for leaves/doors/lanterns, real alpha for stained
glass. No sun shadows, no bloom, no depth of field - only what the game itself shows.

jobs.json: [{"scene": quads.json, "out": png, "res": [w, h], "yaw": deg, "pitch": deg,
             "ortho": scale, "target": [x, y, z], "bg": [r, g, b]}]
Camera yaw 0 looks north from the south; positive yaw swings the camera towards the east.
"""
import json
import math
import sys

import bpy
from mathutils import Vector

_mats = {}


def srgb_to_lin(c):
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def material(key, path, alpha):
    k = (key, alpha)
    if k in _mats:
        return _mats[k]
    mat = bpy.data.materials.new("m_" + key)
    mat.use_nodes = True
    nt = mat.node_tree
    for n in list(nt.nodes):
        nt.nodes.remove(n)
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    img = bpy.data.images.load(path, check_existing=True)
    img.colorspace_settings.name = "sRGB"
    tex = nt.nodes.new("ShaderNodeTexImage")
    tex.image = img
    tex.interpolation = "Closest"
    tex.extension = "REPEAT"
    attr = nt.nodes.new("ShaderNodeAttribute")
    attr.attribute_name = "shade"
    mul = nt.nodes.new("ShaderNodeVectorMath")
    mul.operation = "MULTIPLY"
    nt.links.new(tex.outputs["Color"], mul.inputs[0])
    nt.links.new(attr.outputs["Color"], mul.inputs[1])
    emit = nt.nodes.new("ShaderNodeEmission")
    emit.inputs["Strength"].default_value = 1.0
    nt.links.new(mul.outputs["Vector"], emit.inputs["Color"])
    if alpha == "opaque":
        nt.links.new(emit.outputs["Emission"], out.inputs["Surface"])
    else:
        transp = nt.nodes.new("ShaderNodeBsdfTransparent")
        mix = nt.nodes.new("ShaderNodeMixShader")
        fac = tex.outputs["Alpha"]
        if alpha == "cut":
            gt = nt.nodes.new("ShaderNodeMath")
            gt.operation = "GREATER_THAN"
            gt.inputs[1].default_value = 0.1
            nt.links.new(fac, gt.inputs[0])
            fac = gt.outputs["Value"]
        nt.links.new(fac, mix.inputs["Fac"])
        nt.links.new(transp.outputs["BSDF"], mix.inputs[1])
        nt.links.new(emit.outputs["Emission"], mix.inputs[2])
        nt.links.new(mix.outputs["Shader"], out.inputs["Surface"])
    _mats[k] = mat
    return mat


def clear():
    for o in list(bpy.data.objects):
        bpy.data.objects.remove(o, do_unlink=True)
    for coll in (bpy.data.meshes, bpy.data.materials, bpy.data.cameras):
        for b in list(coll):
            coll.remove(b)
    _mats.clear()


def build(scene_data):
    groups = {}
    for q in scene_data["quads"]:
        groups.setdefault((q["t"], q["a"]), []).append(q)
    for (key, alpha), qs in groups.items():
        verts, faces, uvs, cols = [], [], [], []
        for q in qs:
            b = len(verts)
            for (x, y, z) in q["v"]:
                verts.append((x, -z, y))
            faces.append((b, b + 1, b + 2, b + 3))
            uvs.extend(q["uv"])
            cols.extend(q["c"])
        mesh = bpy.data.meshes.new("q_" + key)
        mesh.from_pydata(verts, [], faces)
        uvl = mesh.uv_layers.new(name="UVMap")
        ca = mesh.color_attributes.new("shade", "FLOAT_COLOR", "CORNER")
        for poly in mesh.polygons:
            for li in poly.loop_indices:
                vi = mesh.loops[li].vertex_index
                uvl.data[li].uv = uvs[vi]
                c = srgb_to_lin(cols[vi])
                ca.data[li].color = (c, c, c, 1.0)
        mesh.materials.append(material(key, scene_data["textures"][key], alpha))
        mesh.update()
        obj = bpy.data.objects.new("q_" + key, mesh)
        bpy.context.scene.collection.objects.link(obj)


def setup(job):
    sc = bpy.context.scene
    try:
        sc.render.engine = "CYCLES"
        sc.cycles.samples = job.get("samples", 24)
        sc.cycles.use_denoising = False
        sc.cycles.max_bounces = 0
        sc.cycles.transparent_max_bounces = 64
        sc.cycles.filter_width = 0.8
        try:
            prefs = bpy.context.preferences.addons["cycles"].preferences
            for dev_type in ("OPTIX", "CUDA"):
                try:
                    prefs.compute_device_type = dev_type
                    prefs.get_devices()
                    if any(d.type == dev_type for d in prefs.devices):
                        for d in prefs.devices:
                            d.use = d.type == dev_type
                        sc.cycles.device = "GPU"
                        break
                except Exception:
                    continue
        except Exception:
            pass
    except Exception as e:
        print("cycles unavailable", e)
        sc.render.engine = "BLENDER_EEVEE"
    sc.view_settings.view_transform = "Standard"
    sc.view_settings.look = "None"
    world = bpy.data.worlds.get("World") or bpy.data.worlds.new("World")
    sc.world = world
    world.use_nodes = True
    bg = world.node_tree.nodes.get("Background")
    r, g, b = job.get("bg", [0.75, 0.82, 0.90])
    bg.inputs["Color"].default_value = (srgb_to_lin(r), srgb_to_lin(g), srgb_to_lin(b), 1.0)
    bg.inputs["Strength"].default_value = 1.0
    w, h = job.get("res", [1400, 1000])
    sc.render.resolution_x, sc.render.resolution_y = w, h
    sc.render.resolution_percentage = 100
    sc.render.film_transparent = False
    cam_data = bpy.data.cameras.new("cam")
    cam = bpy.data.objects.new("cam", cam_data)
    sc.collection.objects.link(cam)
    sc.camera = cam
    yaw, pitch = math.radians(job["yaw"]), math.radians(job["pitch"])
    tx, ty, tz = job["target"]
    target = Vector((tx, -tz, ty))
    d_mc = (math.sin(yaw) * math.cos(pitch), math.sin(pitch), math.cos(yaw) * math.cos(pitch))
    direction = Vector((d_mc[0], -d_mc[2], d_mc[1]))
    cam.location = target + direction * 200
    cam.rotation_euler = (-direction).to_track_quat("-Z", "Y").to_euler()
    cam_data.type = "ORTHO"
    cam_data.ortho_scale = job["ortho"]
    cam_data.clip_end = 1000
    cam_data.sensor_fit = "AUTO"


def main():
    jobs = json.load(open(sys.argv[sys.argv.index("--") + 1]))
    for job in jobs:
        clear()
        data = json.load(open(job["scene"]))
        build(data)
        setup(job)
        sc = bpy.context.scene
        sc.render.filepath = job["out"]
        sc.render.image_settings.file_format = "PNG"
        bpy.ops.render.render(write_still=True)
        print("RENDERED", job["out"])


main()
